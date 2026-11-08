package api

import (
	"log"
	"net/http"
	"time"
)

type recognitionResponse struct {
	http.ResponseWriter
	status int
	reason string
}

func (w *recognitionResponse) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *recognitionResponse) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(data)
}

// 只记录固定类型、结果码和耗时，不记录账号、文件名、正文或请求头。
func observeRecognition(next http.Handler, fixedKind string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		kind := fixedKind
		if kind == "" {
			kind = r.PathValue("kind")
		}
		switch kind {
		case "menu", "notice", "schedule":
		default:
			kind = "other"
		}
		observed := &recognitionResponse{ResponseWriter: w, reason: "ok"}
		started := time.Now()
		completed := false
		defer func() {
			if !completed {
				observed.status = http.StatusInternalServerError
				observed.reason = "internal_error"
			}
			if observed.status == 0 {
				observed.status = http.StatusOK
			}
			log.Printf("识别诊断 功能=%s 状态=%d 结果=%s 耗时毫秒=%d", kind, observed.status, observed.reason, time.Since(started).Milliseconds())
		}()
		next.ServeHTTP(observed, r)
		completed = true
	})
}
