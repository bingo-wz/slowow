package recognition

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNoticeAcceptsLargeValidModelResponse(t *testing.T) {
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		content := `{"items":[{"title":"虚构班会","date":null,"time":null,"evidence":"","uncertain":true}],"warnings":[]}`
		_ = json.NewEncoder(w).Encode(map[string]any{"padding": strings.Repeat("x", 600<<10), "choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": content}}}})
	}))
	defer mock.Close()
	key := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(key, []byte("example-test-key"), 0600); err != nil {
		t.Fatal(err)
	}
	client := &Client{endpoint: mock.URL, model: "test-model", keyFile: key, http: mock.Client()}
	result, err := client.Notice(context.Background(), "虚构通知", nil)
	if err != nil || len(result.Items) != 1 {
		t.Fatalf("完整响应被错误截断: %v", err)
	}
}

func TestMalformedModelEnvelopeDoesNotLeakContent(t *testing.T) {
	var output bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&output)
	defer log.SetOutput(previous)
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, `{"choices":[{"message":{"content":["虚构学生姓名"]}}]}`)
	}))
	defer mock.Close()
	key := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(key, []byte("example-test-key"), 0600); err != nil {
		t.Fatal(err)
	}
	client := &Client{endpoint: mock.URL, model: "test-model", keyFile: key, http: mock.Client()}
	if _, err := client.Notice(context.Background(), "虚构通知", nil); err != ErrInvalidResult {
		t.Fatalf("异常响应应被拒绝: %v", err)
	}
	if !strings.Contains(output.String(), "字段=content") || strings.Contains(output.String(), "虚构学生姓名") {
		t.Fatal("外层诊断未正确保护内容")
	}
}

func TestNoticeRejectsMalformedDatesAndTruncatedResults(t *testing.T) {
	badDate := "2026-02-30"
	if validateNotice(NoticeResult{Items: []NoticeItem{{Title: "开会", Date: &badDate}}}) == nil {
		t.Fatal("不能接受不存在的日期")
	}
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, `{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}`)
	}))
	defer mock.Close()
	key := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(key, []byte("example-test-key"), 0600); err != nil {
		t.Fatal(err)
	}
	client := &Client{endpoint: mock.URL, model: "test-model", keyFile: key, http: mock.Client()}
	if _, err := client.Notice(context.Background(), "虚构通知", nil); err != ErrInvalidResult {
		t.Fatalf("截断结果应拒绝: %v", err)
	}
}

func TestOnlyOfficialEndpointsAreAllowed(t *testing.T) {
	if _, err := New("https://example.com/v1/chat/completions", "model", "/tmp/key"); err == nil {
		t.Fatal("不能将密钥发送到未知地址")
	}
	if _, err := New("https://api.xiaomimimo.com/v1/chat/completions", "model", "/tmp/key"); err != nil {
		t.Fatal(err)
	}
}
