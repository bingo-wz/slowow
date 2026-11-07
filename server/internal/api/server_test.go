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

	"app.manman/server/internal/attachments"
	"app.manman/server/internal/auth"
	"app.manman/server/internal/model"
	"app.manman/server/internal/recognition"
	"app.manman/server/internal/store"
)

type fakeVerifier struct{}

func (fakeVerifier) Verify(_ context.Context, token string) (string, error) {
	if token != "valid" {
		return "", auth.ErrUnauthenticated
	}
	return "verified-owner", nil
}

type fakeStore struct {
	owner    string
	conflict bool
}

func (s *fakeStore) Apply(_ context.Context, owner string, op model.Operation) (model.Record, error) {
	s.owner = owner
	if s.conflict {
		return model.Record{}, &store.ConflictError{Current: &model.Record{ID: op.ID, Kind: op.Kind, Version: 2, Payload: json.RawMessage(`{}`)}}
	}
	return model.Record{ID: op.ID, Kind: op.Kind, Version: 1, Payload: op.Payload}, nil
}
func (s *fakeStore) Pull(_ context.Context, owner string, after int64, _ int) (model.Page, error) {
	s.owner = owner
	return model.Page{Records: []model.Record{}, NextCursor: after}, nil
}

func TestProtectedOperationsAndVerifiedOwner(t *testing.T) {
	backend := &fakeStore{}
	server := New(fakeVerifier{}, backend, nil, nil, nil)
	body := `{"operationId":"op-1","kind":"student","id":"student-1","baseVersion":0,"payload":{"name":"虚构学生"}}`
	req := httptest.NewRequest(http.MethodPost, "/v1/operations", strings.NewReader(body))
	result := httptest.NewRecorder()
	server.ServeHTTP(result, req)
	if result.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated request: %d", result.Code)
	}

	req = httptest.NewRequest(http.MethodPost, "/v1/operations", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer valid")
	result = httptest.NewRecorder()
	server.ServeHTTP(result, req)
	if result.Code != http.StatusOK || backend.owner != "verified-owner" {
		t.Fatalf("verified owner: %d %q", result.Code, backend.owner)
	}

	malicious := strings.TrimSuffix(body, "}") + `,"ownerId":"other"}`
	req = httptest.NewRequest(http.MethodPost, "/v1/operations", strings.NewReader(malicious))
	req.Header.Set("Authorization", "Bearer valid")
	result = httptest.NewRecorder()
	server.ServeHTTP(result, req)
	if result.Code != http.StatusBadRequest {
		t.Fatalf("client owner must be rejected: %d", result.Code)
	}
}

func TestConflictsAndValidation(t *testing.T) {
	backend := &fakeStore{conflict: true}
	server := New(fakeVerifier{}, backend, nil, nil, nil)
	req := httptest.NewRequest(http.MethodPost, "/v1/operations", strings.NewReader(`{"operationId":"op-1","kind":"student","id":"student-1","baseVersion":0,"payload":{}}`))
	req.Header.Set("Authorization", "Bearer valid")
	result := httptest.NewRecorder()
	server.ServeHTTP(result, req)
	if result.Code != http.StatusConflict || !strings.Contains(result.Body.String(), `"current"`) {
		t.Fatalf("conflict response: %d %s", result.Code, result.Body)
	}

	req = httptest.NewRequest(http.MethodGet, "/v1/records?after=-1", nil)
	req.Header.Set("Authorization", "Bearer valid")
	result = httptest.NewRecorder()
	server.ServeHTTP(result, req)
	if result.Code != http.StatusBadRequest {
		t.Fatalf("negative cursor: %d", result.Code)
	}
	largeBody := `{"operationId":"op-4","kind":"notice","id":"notice-1","baseVersion":0,"payload":{"text":"` + strings.Repeat("x", 70<<10) + `"}}`
	req = httptest.NewRequest(http.MethodPost, "/v1/operations", strings.NewReader(largeBody))
	req.Header.Set("Authorization", "Bearer valid")
	result = httptest.NewRecorder()
	server.ServeHTTP(result, req)
	if result.Code != http.StatusBadRequest {
		t.Fatalf("oversized record should be rejected: %d", result.Code)
	}
}

type twoOwnersVerifier struct{}

func (twoOwnersVerifier) Verify(_ context.Context, token string) (string, error) {
	if token == "a" || token == "b" {
		return token, nil
	}
	return "", auth.ErrUnauthenticated
}

func TestAttachmentEndpointsRequireOwnerAndKeepFilesSeparate(t *testing.T) {
	files, err := attachments.New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	server := New(twoOwnersVerifier{}, &fakeStore{}, files, nil, nil)
	id := "a5abf451-028c-49ba-a3ac-c0f9656bd81d"
	image := append([]byte{137, 80, 78, 71, 13, 10, 26, 10}, bytes.Repeat([]byte{1}, 64)...)
	request := func(method, token string, data []byte) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, "/v1/attachments/"+id, bytes.NewReader(data))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		response := httptest.NewRecorder()
		server.ServeHTTP(response, req)
		return response
	}
	if got := request(http.MethodPut, "", image); got.Code != http.StatusUnauthorized {
		t.Fatalf("未登录上传: %d", got.Code)
	}
	if got := request(http.MethodPut, "a", image); got.Code != http.StatusCreated {
		t.Fatalf("上传: %d", got.Code)
	}
	if got := request(http.MethodGet, "b", nil); got.Code != http.StatusNotFound {
		t.Fatalf("跨账号读取: %d", got.Code)
	}
	if got := request(http.MethodGet, "a", nil); got.Code != http.StatusOK || !bytes.Equal(got.Body.Bytes(), image) || got.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("本人读取原图: %d", got.Code)
	}
}

type fakeRecognizer struct{ called int }

func (f *fakeRecognizer) Menu(_ context.Context, week time.Time, images []recognition.Image) (recognition.MenuResult, error) {
	f.called++
	return recognition.MenuResult{Days: []recognition.MenuDay{{Date: week.Format("2006-01-02"), Dishes: []string{"虚构菜品"}}}}, nil
}
func (f *fakeRecognizer) Notice(_ context.Context, _ string, _ []recognition.Image) (recognition.NoticeResult, error) {
	f.called++
	return recognition.NoticeResult{Items: []recognition.NoticeItem{}}, nil
}
func (f *fakeRecognizer) Schedule(_ context.Context, _ string, _ []recognition.Image) (recognition.ScheduleResult, error) {
	f.called++
	return recognition.ScheduleResult{Lessons: []recognition.ScheduleLesson{{Weekday: 1, Slot: "第一节", Subject: "语文"}}}, nil
}

func TestRecognitionRequiresConfirmationAndOwnImages(t *testing.T) {
	files, err := attachments.New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	id := "a5abf451-028c-49ba-a3ac-c0f9656bd81d"
	image := append([]byte{137, 80, 78, 71, 13, 10, 26, 10}, bytes.Repeat([]byte{1}, 64)...)
	if _, err = files.Put("a", id, bytes.NewReader(image)); err != nil {
		t.Fatal(err)
	}
	model := &fakeRecognizer{}
	dir := t.TempDir()
	digest := sha256.Sum256([]byte("12345678"))
	if err := os.WriteFile(filepath.Join(dir, "pair.hash"), []byte(hex.EncodeToString(digest[:])), 0600); err != nil {
		t.Fatal(err)
	}
	gate, err := recognition.NewGate(filepath.Join(dir, "pair.hash"), filepath.Join(dir, "owner"))
	if err != nil {
		t.Fatal(err)
	}
	if err := gate.Claim("a", "12345678", time.Now().Add(-10*time.Second)); err != nil {
		t.Fatal(err)
	}
	if err := gate.Claim("b", "12345678", time.Now()); err != nil {
		t.Fatal(err)
	}
	server := New(twoOwnersVerifier{}, &fakeStore{}, files, model, gate)
	request := func(token, body string) int {
		req := httptest.NewRequest(http.MethodPost, "/v1/recognize/menu", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		result := httptest.NewRecorder()
		server.ServeHTTP(result, req)
		return result.Code
	}
	body := `{"confirmed":true,"imageIds":["` + id + `"],"text":"","weekStart":"2026-11-09"}`
	if got := request("b", body); got != http.StatusNotFound || model.called != 0 {
		t.Fatalf("跨账号图片: %d", got)
	}
	unconfirmed := strings.Replace(body, `"confirmed":true`, `"confirmed":false`, 1)
	if got := request("a", unconfirmed); got != http.StatusBadRequest || model.called != 0 {
		t.Fatalf("未确认: %d", got)
	}
	if got := request("a", body); got != http.StatusOK || model.called != 1 {
		t.Fatalf("确认识别: %d", got)
	}
	if got := request("a", body); got != http.StatusTooManyRequests || model.called != 1 {
		t.Fatalf("短时间重复请求: %d", got)
	}
}

func TestRecognitionClaimBindsTwoAccounts(t *testing.T) {
	dir := t.TempDir()
	digest := sha256.Sum256([]byte("12345678"))
	hashFile := filepath.Join(dir, "pair.hash")
	if err := os.WriteFile(hashFile, []byte(hex.EncodeToString(digest[:])), 0600); err != nil {
		t.Fatal(err)
	}
	gate, _ := recognition.NewGate(hashFile, filepath.Join(dir, "owner"))
	if err := gate.Claim("a", "12345678", time.Now().Add(-10*time.Second)); err != nil {
		t.Fatal(err)
	}
	server := New(twoOwnersVerifier{}, &fakeStore{}, nil, &fakeRecognizer{}, gate)
	request := func(token string) int {
		req := httptest.NewRequest(http.MethodPost, "/v1/recognize/claim", strings.NewReader(`{"code":"12345678"}`))
		req.Header.Set("Authorization", "Bearer "+token)
		response := httptest.NewRecorder()
		server.ServeHTTP(response, req)
		return response.Code
	}
	if got := request("a"); got != http.StatusOK {
		t.Fatalf("绑定失败: %d", got)
	}
	if got := request("b"); got != http.StatusOK || !gate.Allowed("a") || !gate.Allowed("b") {
		t.Fatalf("第二个授权账号应可绑定: %d", got)
	}
}

func TestScheduleRecognitionRequiresConfirmationAndBoundAccount(t *testing.T) {
	dir := t.TempDir()
	digest := sha256.Sum256([]byte("12345678"))
	hashFile := filepath.Join(dir, "pair.hash")
	if err := os.WriteFile(hashFile, []byte(hex.EncodeToString(digest[:])), 0600); err != nil {
		t.Fatal(err)
	}
	gate, _ := recognition.NewGate(hashFile, filepath.Join(dir, "owner"))
	if err := gate.Claim("a", "12345678", time.Now().Add(-10*time.Second)); err != nil {
		t.Fatal(err)
	}
	model := &fakeRecognizer{}
	server := New(twoOwnersVerifier{}, &fakeStore{}, nil, model, gate)
	image := []byte{137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0}
	send := func(token string, confirmed bool) int {
		body, _ := json.Marshal(scheduleRequest{Confirmed: confirmed, Files: []scheduleFile{{Kind: "image/png", Data: image}}})
		req := httptest.NewRequest(http.MethodPost, "/v1/recognize/schedule", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		result := httptest.NewRecorder()
		server.ServeHTTP(result, req)
		return result.Code
	}
	if got := send("b", true); got != http.StatusForbidden || model.called != 0 {
		t.Fatalf("未绑定账号: %d", got)
	}
	if got := send("a", false); got != http.StatusBadRequest || model.called != 0 {
		t.Fatalf("未确认请求: %d", got)
	}
	if got := send("a", true); got != http.StatusOK || model.called != 1 {
		t.Fatalf("正常识别: %d", got)
	}
}

func TestLegacyExcelRecognitionShowsMergeWarning(t *testing.T) {
	dir := t.TempDir()
	digest := sha256.Sum256([]byte("12345678"))
	hashFile := filepath.Join(dir, "pair.hash")
	if err := os.WriteFile(hashFile, []byte(hex.EncodeToString(digest[:])), 0600); err != nil {
		t.Fatal(err)
	}
	gate, _ := recognition.NewGate(hashFile, filepath.Join(dir, "owner"))
	if err := gate.Claim("a", "12345678", time.Now()); err != nil {
		t.Fatal(err)
	}
	model := &fakeRecognizer{}
	server := New(twoOwnersVerifier{}, &fakeStore{}, nil, model, gate)
	data, err := os.ReadFile("../recognition/testdata/fictional.xls")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(scheduleRequest{Confirmed: true, Files: []scheduleFile{{Kind: "xls", Data: data}}})
	request := httptest.NewRequest(http.MethodPost, "/v1/recognize/schedule", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer a")
	result := httptest.NewRecorder()
	server.ServeHTTP(result, request)
	if result.Code != http.StatusOK || !strings.Contains(result.Body.String(), "旧版 XLS") {
		t.Fatalf("旧版表格缺少合并单元格提示: %d %s", result.Code, result.Body.String())
	}
}
