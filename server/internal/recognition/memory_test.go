package recognition

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestScheduleMemoryBudget(t *testing.T) {
	if os.Getenv("MANMAN_MEMORY_TEST") != "1" {
		t.Skip("仅在资源限制下运行")
	}
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		content := `{"slots":[{"weekday":1,"title":"第一节","start":"09:05","end":"09:45"}],"lessons":[],"warnings":[]}`
		chunk, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": content}, "finish_reason": nil}}})
		fmt.Fprintf(w, "data: %s\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", chunk)
	}))
	defer mock.Close()
	key := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(key, []byte("example-test-key"), 0600); err != nil {
		t.Fatal(err)
	}
	file := bytes.Repeat([]byte{42}, 6<<20)
	payload, err := json.Marshal(map[string]any{"confirmed": true, "files": []any{
		map[string]any{"kind": "image/png", "data": file}, map[string]any{"kind": "image/png", "data": file},
	}})
	if err != nil {
		t.Fatal(err)
	}
	file = nil
	runtime.GC()
	var input struct {
		Files []struct {
			Kind string `json:"kind"`
			Data []byte `json:"data"`
		} `json:"files"`
	}
	if err := json.NewDecoder(bytes.NewReader(payload)).Decode(&input); err != nil {
		t.Fatal(err)
	}
	images := make([]Image, 0, 2)
	for _, entry := range input.Files {
		images = append(images, Image{MIME: entry.Kind, Data: entry.Data})
	}
	client := &Client{endpoint: mock.URL, model: "test-model", keyFile: key, http: mock.Client()}
	if _, err := client.Schedule(context.Background(), images, func() {}); err != nil {
		t.Fatal(err)
	}
	runtime.KeepAlive(payload)
	runtime.KeepAlive(input)
}
