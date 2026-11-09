package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"app.manman/server/internal/recognition"
)

type scheduleImage struct {
	Kind string `json:"kind"`
	Data []byte `json:"data"`
}

type scheduleRequest struct {
	Confirmed bool            `json:"confirmed"`
	Files     []scheduleImage `json:"files"`
}

func (s *Server) recognizeSchedule(w http.ResponseWriter, r *http.Request) {
	if s.recognizer == nil || s.gate == nil {
		writeError(w, http.StatusServiceUnavailable, "recognition_unavailable", "识别服务尚未配置。")
		return
	}
	owner := r.Context().Value(ownerKey{}).(string)
	if !s.gate.Allowed(owner) {
		writeError(w, http.StatusForbidden, "recognition_not_bound", "当前账号尚未绑定识别服务。")
		return
	}
	select {
	case s.recognizing <- struct{}{}:
		defer func() { <-s.recognizing }()
	default:
		writeError(w, http.StatusTooManyRequests, "recognition_busy", "识别繁忙，请稍后再试。")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 18<<20)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var input scheduleRequest
	if decoder.Decode(&input) != nil || !input.Confirmed || len(input.Files) < 1 || len(input.Files) > 2 {
		writeError(w, http.StatusBadRequest, "invalid_request", "请选择一至两张图片并确认发送。")
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request", "请求内容无效。")
		return
	}
	images := make([]recognition.Image, 0, len(input.Files))
	total := 0
	for _, file := range input.Files {
		total += len(file.Data)
		if len(file.Data) == 0 || len(file.Data) > 8<<20 || total > 12<<20 {
			writeError(w, http.StatusRequestEntityTooLarge, "images_too_large", "每张图片不能超过 8 MB，合计不能超过 12 MB。")
			return
		}
		if (file.Kind != "image/jpeg" && file.Kind != "image/png" && file.Kind != "image/webp") || http.DetectContentType(file.Data) != file.Kind {
			writeError(w, http.StatusBadRequest, "invalid_image", "请选择有效的 JPEG、PNG 或 WebP 图片。")
			return
		}
		images = append(images, recognition.Image{MIME: file.Kind, Data: file.Data})
	}
	if !s.allowRecognition(owner, time.Now()) {
		writeError(w, http.StatusTooManyRequests, "recognition_limit", "识别次数过多，请稍后再试。")
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "stream_unavailable", "识别连接暂不可用。")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("X-Accel-Buffering", "no")
	if !scheduleEvent(w, flusher, map[string]any{"type": "phase", "phase": "received"}) {
		return
	}

	type outcome struct {
		value recognition.ScheduleResult
		err   error
	}
	result := make(chan outcome, 1)
	started := make(chan struct{}, 1)
	go func() {
		value, err := s.recognizer.Schedule(r.Context(), images, func() {
			select {
			case started <- struct{}{}:
			default:
			}
		})
		result <- outcome{value, err}
	}()
	ticker := time.NewTicker(8 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ticker.C:
			if !scheduleEvent(w, flusher, map[string]any{"type": "heartbeat"}) {
				return
			}
		case <-started:
			if !scheduleEvent(w, flusher, map[string]any{"type": "phase", "phase": "generating"}) {
				return
			}
		case output := <-result:
			if output.err != nil {
				message, reason := "识别服务暂不可用，请稍后重试。", "model_unavailable"
				switch {
				case errors.Is(output.err, recognition.ErrInvalidResult):
					message, reason = "识别结果不完整，请重新选择清晰的图片后重试。", "invalid_result"
				case errors.Is(output.err, recognition.ErrUnauthorized):
					message, reason = "识别服务密钥或权限无效，请检查服务配置。", "recognition_auth"
				case errors.Is(output.err, recognition.ErrRateLimited):
					message, reason = "识别服务额度不足或请求过于频繁。", "recognition_quota"
				}
				if diagnostic, ok := w.(*recognitionResponse); ok {
					diagnostic.reason = reason
				}
				scheduleEvent(w, flusher, map[string]any{"type": "error", "message": message})
				return
			}
			scheduleEvent(w, flusher, map[string]any{"type": "result", "result": output.value})
			return
		}
	}
}

func scheduleEvent(w io.Writer, flusher http.Flusher, value any) bool {
	data, err := json.Marshal(value)
	if err != nil {
		return false
	}
	if _, err = fmt.Fprintf(w, "data: %s\n\n", data); err != nil {
		return false
	}
	flusher.Flush()
	return true
}
