package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"app.manman/server/internal/attachments"
	"app.manman/server/internal/auth"
	"app.manman/server/internal/model"
	"app.manman/server/internal/recognition"
	"app.manman/server/internal/store"
)

type Server struct {
	verifier    auth.Verifier
	store       store.Store
	mux         *http.ServeMux
	attachments *attachments.Store
	uploads     chan struct{}
	recognizer  recognition.Service
	gate        *recognition.Gate
	recognizing chan struct{}
	usageMu     sync.Mutex
	usage       map[string]recognitionUsage
}

type ownerKey struct{}

func New(verifier auth.Verifier, repository store.Store, files *attachments.Store, recognizer recognition.Service, gate *recognition.Gate) *Server {
	s := &Server{verifier: verifier, store: repository, attachments: files, uploads: make(chan struct{}, 4),
		recognizer: recognizer, gate: gate, recognizing: make(chan struct{}, 1), usage: make(map[string]recognitionUsage), mux: http.NewServeMux()}
	s.mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	s.mux.Handle("POST /v1/operations", s.authorize(http.HandlerFunc(s.apply)))
	s.mux.Handle("GET /v1/records", s.authorize(http.HandlerFunc(s.pull)))
	s.mux.Handle("PUT /v1/attachments/{id}", s.authorize(http.HandlerFunc(s.putAttachment)))
	s.mux.Handle("GET /v1/attachments/{id}", s.authorize(http.HandlerFunc(s.getAttachment)))
	s.mux.Handle("POST /v1/recognize/{kind}", observeRecognition(s.authorize(http.HandlerFunc(s.recognize)), ""))
	s.mux.Handle("POST /v1/recognize/claim", s.authorize(http.HandlerFunc(s.claimRecognition)))
	s.mux.Handle("POST /v1/recognize/schedule", observeRecognition(s.authorize(http.HandlerFunc(s.recognizeSchedule)), "schedule"))
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	s.mux.ServeHTTP(w, r)
}

func (s *Server) authorize(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if !strings.HasPrefix(header, "Bearer ") || strings.TrimSpace(strings.TrimPrefix(header, "Bearer ")) == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "请先登录。")
			return
		}
		token := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
		owner, err := s.verifier.Verify(r.Context(), token)
		if err != nil {
			if errors.Is(err, auth.ErrUnauthenticated) {
				writeError(w, http.StatusUnauthorized, "unauthorized", "登录已过期，请重新登录。")
			} else {
				writeError(w, http.StatusServiceUnavailable, "auth_unavailable", "身份验证暂时不可用，请稍后重试。")
			}
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ownerKey{}, owner)))
	})
}

var identifier = regexp.MustCompile(`^[A-Za-z0-9_-]{1,100}$`)
var allowedKinds = map[string]bool{
	"student": true, "leave": true, "meal_override": true, "lunch_review": true,
	"marking": true, "menu": true, "menu_week": true, "notice": true, "task": true, "lesson": true, "time_slot": true, "attachment": true, "settings": true,
}

func (s *Server) putAttachment(w http.ResponseWriter, r *http.Request) {
	if s.attachments == nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "附件暂时无法保存。")
		return
	}
	select {
	case s.uploads <- struct{}{}:
		defer func() { <-s.uploads }()
	default:
		writeError(w, http.StatusTooManyRequests, "upload_busy", "上传繁忙，请稍后重试。")
		return
	}
	owner := r.Context().Value(ownerKey{}).(string)
	created, err := s.attachments.Put(owner, r.PathValue("id"), r.Body)
	switch {
	case errors.Is(err, attachments.ErrInvalidID), errors.Is(err, attachments.ErrImage):
		writeError(w, http.StatusBadRequest, "invalid_attachment", "图片格式不受支持。")
	case errors.Is(err, attachments.ErrTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "attachment_too_large", "图片不能超过 8 MB。")
	case errors.Is(err, attachments.ErrQuota):
		writeError(w, http.StatusInsufficientStorage, "attachment_quota", "图片空间已满。")
	case errors.Is(err, attachments.ErrConflict):
		writeError(w, http.StatusConflict, "attachment_conflict", "图片编号已被其他内容使用。")
	case err != nil:
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "附件暂时无法保存。")
	case created:
		writeJSON(w, http.StatusCreated, map[string]bool{"saved": true})
	default:
		writeJSON(w, http.StatusOK, map[string]bool{"saved": true})
	}
}

func (s *Server) getAttachment(w http.ResponseWriter, r *http.Request) {
	if s.attachments == nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "附件暂时无法读取。")
		return
	}
	owner := r.Context().Value(ownerKey{}).(string)
	file, kind, err := s.attachments.Open(owner, r.PathValue("id"))
	if errors.Is(err, os.ErrNotExist) || errors.Is(err, attachments.ErrInvalidID) {
		writeError(w, http.StatusNotFound, "attachment_not_found", "图片不存在。")
		return
	}
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "附件暂时无法读取。")
		return
	}
	defer file.Close()
	w.Header().Set("Content-Type", kind)
	http.ServeContent(w, r, "image", time.Time{}, file)
}

func (s *Server) apply(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var op model.Operation
	if err := decoder.Decode(&op); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "请求内容无效。")
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request", "请求内容无效。")
		return
	}
	var object map[string]json.RawMessage
	if !identifier.MatchString(op.OperationID) || !identifier.MatchString(op.ID) || !allowedKinds[op.Kind] || op.BaseVersion < 0 ||
		len(op.Payload) == 0 || json.Unmarshal(op.Payload, &object) != nil || object == nil {
		writeError(w, http.StatusBadRequest, "invalid_operation", "记录格式无效。")
		return
	}
	owner := r.Context().Value(ownerKey{}).(string)
	record, err := s.store.Apply(r.Context(), owner, op)
	if err != nil {
		var conflict *store.ConflictError
		switch {
		case errors.As(err, &conflict):
			writeJSON(w, http.StatusConflict, map[string]any{"error": "conflict", "message": "云端记录已变化，请核对。", "current": conflict.Current})
		case errors.Is(err, store.ErrOperationReused):
			writeError(w, http.StatusConflict, "operation_reused", "同步操作编号已被其他内容使用。")
		default:
			writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "数据暂时无法同步，请稍后重试。")
		}
		return
	}
	writeJSON(w, http.StatusOK, record)
}

func (s *Server) pull(w http.ResponseWriter, r *http.Request) {
	after := int64(0)
	var err error
	if value := r.URL.Query().Get("after"); value != "" {
		after, err = strconv.ParseInt(value, 10, 64)
	}
	if err != nil || after < 0 {
		writeError(w, http.StatusBadRequest, "invalid_cursor", "同步位置无效。")
		return
	}
	limit := 200
	if value := r.URL.Query().Get("limit"); value != "" {
		limit, err = strconv.Atoi(value)
	}
	if err != nil || limit < 1 || limit > 200 {
		writeError(w, http.StatusBadRequest, "invalid_limit", "同步数量无效。")
		return
	}
	owner := r.Context().Value(ownerKey{}).(string)
	page, err := s.store.Pull(r.Context(), owner, after, limit)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "数据暂时无法同步，请稍后重试。")
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func writeError(w http.ResponseWriter, code int, key, message string) {
	if diagnostic, ok := w.(*recognitionResponse); ok {
		diagnostic.reason = key
	}
	writeJSON(w, code, map[string]string{"error": key, "message": message})
}

func writeJSON(w http.ResponseWriter, code int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(value)
}
