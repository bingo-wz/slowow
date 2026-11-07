package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
	"unicode/utf8"

	"app.manman/server/internal/attachments"
	"app.manman/server/internal/recognition"
)

type recognitionUsage struct {
	day   string
	count int
	last  time.Time
}

type recognitionRequest struct {
	Confirmed bool     `json:"confirmed"`
	ImageIDs  []string `json:"imageIds"`
	Text      string   `json:"text"`
	WeekStart string   `json:"weekStart"`
}

func (s *Server) recognize(w http.ResponseWriter, r *http.Request) {
	if s.recognizer == nil || s.attachments == nil || s.gate == nil {
		writeError(w, http.StatusServiceUnavailable, "recognition_unavailable", "识别服务尚未配置。")
		return
	}
	owner := r.Context().Value(ownerKey{}).(string)
	if !s.gate.Allowed(owner) {
		writeError(w, http.StatusForbidden, "recognition_not_bound", "当前账号尚未绑定识别服务，请在设置中输入授权码。")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var input recognitionRequest
	if err := decoder.Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "请求内容无效。")
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request", "请求内容无效。")
		return
	}
	kind := r.PathValue("kind")
	if !input.Confirmed || len(input.ImageIDs) > 5 || utf8.RuneCountInString(input.Text) > 10000 ||
		(kind != "menu" && kind != "notice") || (kind == "menu" && len(input.ImageIDs) != 1) ||
		(kind == "notice" && strings.TrimSpace(input.Text) == "" && len(input.ImageIDs) == 0) {
		writeError(w, http.StatusBadRequest, "invalid_request", "请先确认要发送的内容。")
		return
	}
	var weekStart time.Time
	var err error
	if kind == "menu" {
		weekStart, err = time.Parse("2006-01-02", input.WeekStart)
		if err != nil || weekStart.Weekday() != time.Monday {
			writeError(w, http.StatusBadRequest, "invalid_week", "请选择周一作为菜单起始日期。")
			return
		}
	}
	select {
	case s.recognizing <- struct{}{}:
		defer func() { <-s.recognizing }()
	default:
		writeError(w, http.StatusTooManyRequests, "recognition_busy", "识别繁忙，请稍后再试。")
		return
	}
	images := make([]recognition.Image, 0, len(input.ImageIDs))
	var total int64
	seen := make(map[string]bool)
	for _, id := range input.ImageIDs {
		if seen[id] {
			writeError(w, http.StatusBadRequest, "duplicate_image", "不能重复选择同一张图片。")
			return
		}
		seen[id] = true
		file, kind, err := s.attachments.Open(owner, id)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) || errors.Is(err, attachments.ErrInvalidID) {
				writeError(w, http.StatusNotFound, "image_not_found", "图片尚未同步或不存在。")
			} else {
				writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "图片暂时无法读取。")
			}
			return
		}
		info, err := file.Stat()
		if err != nil {
			file.Close()
			writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "图片暂时无法读取。")
			return
		}
		total += info.Size()
		if total > 12<<20 {
			file.Close()
			writeError(w, http.StatusRequestEntityTooLarge, "images_too_large", "一次识别的图片合计不能超过 12 MB。")
			return
		}
		data, err := io.ReadAll(io.LimitReader(file, attachments.MaxSize+1))
		file.Close()
		if err != nil || len(data) > attachments.MaxSize {
			writeError(w, http.StatusServiceUnavailable, "storage_unavailable", "图片暂时无法读取。")
			return
		}
		images = append(images, recognition.Image{MIME: kind, Data: data})
	}
	if !s.allowRecognition(owner, time.Now()) {
		writeError(w, http.StatusTooManyRequests, "recognition_limit", "识别次数过多，请稍后再试。")
		return
	}
	if kind == "menu" {
		result, err := s.recognizer.Menu(r.Context(), weekStart, images)
		if err != nil {
			s.recognitionError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
		return
	}
	result, err := s.recognizer.Notice(r.Context(), input.Text, images)
	if err != nil {
		s.recognitionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) claimRecognition(w http.ResponseWriter, r *http.Request) {
	if s.recognizer == nil || s.gate == nil {
		writeError(w, http.StatusServiceUnavailable, "recognition_unavailable", "识别服务尚未配置。")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var input struct {
		Code string `json:"code"`
	}
	if err := decoder.Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "授权码格式无效。")
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request", "授权码格式无效。")
		return
	}
	owner := r.Context().Value(ownerKey{}).(string)
	err := s.gate.Claim(owner, input.Code, time.Now())
	switch {
	case errors.Is(err, recognition.ErrInvalidCode):
		writeError(w, http.StatusBadRequest, "invalid_code", "授权码不正确。")
	case errors.Is(err, recognition.ErrClaimRate):
		writeError(w, http.StatusTooManyRequests, "claim_limit", "尝试过于频繁，请稍后再试。")
	case errors.Is(err, recognition.ErrNotBound):
		writeError(w, http.StatusForbidden, "already_bound", "识别服务已绑定两个账号。")
	case err != nil:
		writeError(w, http.StatusServiceUnavailable, "recognition_unavailable", "识别服务暂不可用。")
	default:
		writeJSON(w, http.StatusOK, map[string]bool{"bound": true})
	}
}

func (s *Server) recognitionError(w http.ResponseWriter, err error) {
	if errors.Is(err, recognition.ErrInvalidResult) {
		writeError(w, http.StatusBadGateway, "invalid_result", "识别结果不完整，请重试或手动录入。")
	} else if errors.Is(err, recognition.ErrUnauthorized) {
		writeError(w, http.StatusServiceUnavailable, "recognition_auth", "识别服务密钥或权限无效，请检查服务配置。")
	} else if errors.Is(err, recognition.ErrRateLimited) {
		writeError(w, http.StatusTooManyRequests, "recognition_quota", "识别服务额度不足或请求过于频繁。")
	} else {
		writeError(w, http.StatusServiceUnavailable, "recognition_unavailable", "识别服务暂不可用，请稍后再试。")
	}
}

func (s *Server) allowRecognition(owner string, now time.Time) bool {
	s.usageMu.Lock()
	defer s.usageMu.Unlock()
	day := now.In(time.FixedZone("China", 8*60*60)).Format("2006-01-02")
	if len(s.usage) > 5000 {
		for id, usage := range s.usage {
			if usage.day != day {
				delete(s.usage, id)
			}
		}
		if len(s.usage) > 5000 {
			return false
		}
	}
	usage := s.usage[owner]
	if usage.day != day {
		usage = recognitionUsage{day: day}
	}
	if usage.count >= 30 || (!usage.last.IsZero() && now.Sub(usage.last) < 5*time.Second) {
		return false
	}
	usage.count++
	usage.last = now
	s.usage[owner] = usage
	return true
}
