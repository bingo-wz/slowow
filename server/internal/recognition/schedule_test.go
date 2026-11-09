package recognition

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestScheduleStreamsTwoImagesAndKeepsIncompleteFieldsForReview(t *testing.T) {
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			Stream   bool `json:"stream"`
			Messages []struct {
				Content json.RawMessage `json:"content"`
			} `json:"messages"`
		}
		if json.NewDecoder(r.Body).Decode(&input) != nil || !input.Stream || len(input.Messages) != 2 {
			t.Error("请求应采用流式多模态格式")
		}
		var parts []map[string]any
		if json.Unmarshal(input.Messages[1].Content, &parts) != nil || len(parts) != 3 {
			t.Error("应同时发送两张图片")
		}
		content := `{"slots":[{"weekday":"周一","title":"第一节","start":"8：35","end":"09:15"},{"weekday":0,"title":"第二节","start":"不清楚","end":null}],"lessons":[{"weekday":1,"slot":"第一节","subject":"虚构课程"}],"warnings":[]}`
		for _, piece := range []string{content[:len(content)/2], content[len(content)/2:]} {
			data, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": piece}, "finish_reason": nil}}})
			fmt.Fprintf(w, "data: %s\n\n", data)
		}
		fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
	}))
	defer mock.Close()
	key := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(key, []byte("example-test-key"), 0600); err != nil {
		t.Fatal(err)
	}
	client := &Client{endpoint: mock.URL, model: "test-model", keyFile: key, http: mock.Client()}
	started := 0
	result, err := client.Schedule(context.Background(), []Image{{MIME: "image/png", Data: []byte("one")}, {MIME: "image/png", Data: []byte("two")}}, func() { started++ })
	if err != nil {
		t.Fatal(err)
	}
	if started != 1 || len(result.Slots) != 2 || result.Slots[0].Weekday != 1 || result.Slots[0].Start == nil || *result.Slots[0].Start != "08:35" {
		t.Fatalf("流式处理或时间归一化错误: %#v", result.Slots)
	}
	if result.Slots[1].Weekday != 0 || result.Slots[1].Start != nil || len(result.Warnings) == 0 {
		t.Fatal("不完整字段应保留给用户核对")
	}
}

func TestScheduleRejectsTruncatedStreams(t *testing.T) {
	for _, stream := range []string{
		"data: {\"choices\":[{\"delta\":{\"content\":\"{}\"},\"finish_reason\":null}]}\n\n",
		"data: {\"choices\":[{\"delta\":{\"content\":\"{}\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n",
	} {
		if _, err := readModelStream(strings.NewReader(stream), func() {}); err != ErrInvalidResult {
			t.Fatalf("截断结果不能保存: %v", err)
		}
	}
}
