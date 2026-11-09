package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"app.manman/server/internal/recognition"
)

type scheduleFake struct{ received int }

func (f *scheduleFake) Notice(context.Context, string, []recognition.Image) (recognition.NoticeResult, error) {
	return recognition.NoticeResult{}, nil
}
func (f *scheduleFake) Schedule(_ context.Context, images []recognition.Image, output func()) (recognition.ScheduleResult, error) {
	f.received = len(images)
	output()
	time.Sleep(20 * time.Millisecond)
	return recognition.ScheduleResult{Slots: []recognition.ScheduleSlot{{Weekday: 1, Title: "第一节"}}}, nil
}

func TestScheduleRequiresConsentAndStreamsBothImages(t *testing.T) {
	dir := t.TempDir()
	digest := sha256.Sum256([]byte("12345678"))
	hashFile := filepath.Join(dir, "pair.hash")
	if err := os.WriteFile(hashFile, []byte(hex.EncodeToString(digest[:])), 0600); err != nil {
		t.Fatal(err)
	}
	gate, err := recognition.NewGate(hashFile, filepath.Join(dir, "owner"))
	if err != nil {
		t.Fatal(err)
	}
	if err := gate.Claim("a", "12345678", time.Now()); err != nil {
		t.Fatal(err)
	}
	model := &scheduleFake{}
	server := New(twoOwnersVerifier{}, &fakeStore{}, nil, model, gate)
	image := append([]byte{137, 80, 78, 71, 13, 10, 26, 10}, bytes.Repeat([]byte{1}, 64)...)
	body := func(confirmed bool, files []scheduleImage) string {
		data, _ := json.Marshal(scheduleRequest{Confirmed: confirmed, Files: files})
		return string(data)
	}
	request := func(token, contents string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, "/v1/recognize/schedule", strings.NewReader(contents))
		r.Header.Set("Authorization", "Bearer "+token)
		response := httptest.NewRecorder()
		server.ServeHTTP(response, r)
		return response
	}
	files := []scheduleImage{{Kind: "image/png", Data: image}, {Kind: "image/png", Data: image}}
	if got := request("", body(true, files)); got.Code != http.StatusUnauthorized || model.received != 0 {
		t.Fatalf("未登录不能识别: %d", got.Code)
	}
	if got := request("a", body(false, files)); got.Code != http.StatusBadRequest || model.received != 0 {
		t.Fatalf("未确认不能识别: %d", got.Code)
	}
	if got := request("b", body(true, files)); got.Code != http.StatusForbidden || model.received != 0 {
		t.Fatalf("未绑定不能识别: %d", got.Code)
	}
	if got := request("a", body(true, []scheduleImage{{Kind: "image/png", Data: []byte("not-image")}})); got.Code != http.StatusBadRequest {
		t.Fatalf("无效图片应拒绝: %d", got.Code)
	}
	if got := request("a", body(true, append(files, files[0]))); got.Code != http.StatusBadRequest {
		t.Fatalf("不能超过两张图片: %d", got.Code)
	}
	tooLarge := append(append([]byte{}, image...), bytes.Repeat([]byte{0}, (8<<20)-len(image)+1)...)
	if got := request("a", body(true, []scheduleImage{{Kind: "image/png", Data: tooLarge}})); got.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("超过单张大小限制: %d", got.Code)
	}
	response := request("a", body(true, files))
	if response.Code != http.StatusOK || !strings.HasPrefix(response.Header().Get("Content-Type"), "text/event-stream") || model.received != 2 {
		t.Fatal("两张图片应一次发送给识别服务")
	}
	for _, phase := range []string{`"phase":"received"`, `"phase":"generating"`, `"type":"result"`} {
		if !strings.Contains(response.Body.String(), phase) {
			t.Fatalf("缺少真实识别阶段: %s", phase)
		}
	}
}
