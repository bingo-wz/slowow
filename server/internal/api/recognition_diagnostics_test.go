package api

import (
	"bytes"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRecognitionDiagnosticsExcludePrivateInput(t *testing.T) {
	var output bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&output)
	defer log.SetOutput(previous)
	handler := observeRecognition(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusBadRequest, "invalid_request", "请求内容无效")
	}), "schedule")
	req := httptest.NewRequest(http.MethodPost, "/v1/recognize/schedule?private=secret", strings.NewReader("虚构学生姓名"))
	req.Header.Set("Authorization", "Bearer private-token")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	result := output.String()
	if response.Code != http.StatusBadRequest || !strings.Contains(result, "功能=schedule") || !strings.Contains(result, "结果=invalid_request") {
		t.Fatal("没有记录预期诊断码")
	}
	if strings.Contains(result, "虚构学生姓名") || strings.Contains(result, "private-token") || strings.Contains(result, "private=secret") {
		t.Fatal("诊断日志包含隐私内容")
	}
}
