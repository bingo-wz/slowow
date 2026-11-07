package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"app.manman/server/internal/recognition"
)

type scheduleFile struct {
	Kind string `json:"kind"`
	Data []byte `json:"data"`
}
type scheduleRequest struct {
	Confirmed bool           `json:"confirmed"`
	Files     []scheduleFile `json:"files"`
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
	if decoder.Decode(&input) != nil || !input.Confirmed || len(input.Files) == 0 || len(input.Files) > 7 {
		writeError(w, http.StatusBadRequest, "invalid_request", "请先选择文件并确认发送。")
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request", "请求内容无效。")
		return
	}
	var images []recognition.Image
	var tables []string
	legacyExcel := false
	total := 0
	for _, file := range input.Files {
		total += len(file.Data)
		if len(file.Data) == 0 || len(file.Data) > 8<<20 || total > 12<<20 {
			writeError(w, http.StatusRequestEntityTooLarge, "files_too_large", "文件合计不能超过 12 MB。")
			return
		}
		switch file.Kind {
		case "image/jpeg", "image/png", "image/webp":
			if http.DetectContentType(file.Data) != file.Kind {
				writeError(w, http.StatusBadRequest, "invalid_image", "图片格式无效。")
				return
			}
			images = append(images, recognition.Image{MIME: file.Kind, Data: file.Data})
		case "xlsx", "xls":
			table, err := recognition.Spreadsheet(file.Kind, file.Data)
			if err != nil {
				writeError(w, http.StatusBadRequest, "invalid_spreadsheet", "表格无法读取，或超过 5 个工作表、200 行、40 列的限制。")
				return
			}
			tables = append(tables, table)
			legacyExcel = legacyExcel || file.Kind == "xls"
		default:
			writeError(w, http.StatusBadRequest, "invalid_file", "请选择图片、PDF 或 Excel 文件。")
			return
		}
	}
	if len(images) > 5 || len(strings.Join(tables, "\n")) > 30000 {
		writeError(w, http.StatusBadRequest, "files_too_large", "一次最多识别 5 页图片或 PDF。 ")
		return
	}
	if !s.allowRecognition(owner, time.Now()) {
		writeError(w, http.StatusTooManyRequests, "recognition_limit", "识别次数过多，请稍后再试。")
		return
	}
	result, err := s.recognizer.Schedule(r.Context(), strings.Join(tables, "\n"), images)
	if err != nil {
		s.recognitionError(w, err)
		return
	}
	if legacyExcel {
		result.Warnings = append(result.Warnings, "旧版 XLS 的合并单元格可能不完整，请特别核对星期和对应课程；如有误可另存为 XLSX 后重试。")
	}
	writeJSON(w, http.StatusOK, result)
}
