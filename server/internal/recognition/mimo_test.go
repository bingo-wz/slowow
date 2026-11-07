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
	"time"
)

func TestMenuRequestAndResponseAreValidated(t *testing.T) {
	var request map[string]any
	mock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer example-test-key" {
			t.Error("missing authorization")
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
		}
		fmt.Fprint(w, `{"choices":[{"finish_reason":"stop","message":{"content":"{\"days\":[{\"date\":\"2026-11-09\",\"dishes\":[\"番茄炒蛋\"]}],\"warnings\":[]}"}}]}`)
	}))
	defer mock.Close()
	key := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(key, []byte("example-test-key"), 0600); err != nil {
		t.Fatal(err)
	}
	client := &Client{endpoint: mock.URL, model: "test-model", keyFile: key, http: mock.Client()}
	week, _ := time.Parse("2006-01-02", "2026-11-09")
	result, err := client.Menu(context.Background(), week, []Image{{MIME: "image/png", Data: []byte("example")}})
	if err != nil || len(result.Days) != 1 || result.Days[0].Dishes[0] != "番茄炒蛋" {
		t.Fatalf("菜单解析: %#v %v", result, err)
	}
	encoded, _ := json.Marshal(request)
	if !strings.Contains(string(encoded), "data:image/png;base64,") || request["max_completion_tokens"] != float64(4096) {
		t.Fatal("请求缺少图片或输出上限")
	}
	wrongWeek := result
	wrongWeek.Days[0].Date = "2026-11-20"
	if err := validateMenu(wrongWeek, week); err == nil {
		t.Fatal("不能接受目标周以外的日期")
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

func TestMenuUsesVisibleWeekdayBeforeMismatchingDate(t *testing.T) {
	week, _ := time.Parse("2006-01-02", "2026-11-09")
	result := MenuResult{Days: []MenuDay{
		{Weekday: 1, Date: "2026-10-30", Dishes: []string{"甲"}},
		{Weekday: 4, Date: "2026-10-29", Dishes: []string{"乙"}},
		{Date: "2026-10-28", Dishes: []string{"丙"}},
	}}
	if err := normalizeMenu(&result, week); err != nil {
		t.Fatal(err)
	}
	if result.Days[0].Date != "2026-11-09" || result.Days[1].Date != "2026-11-12" || result.Days[2].Date != "2026-11-11" {
		t.Fatalf("星期映射错误: %#v", result.Days)
	}
	if len(result.Warnings) != 2 {
		t.Fatalf("缺少日期冲突或推算提示: %#v", result.Warnings)
	}
	duplicate := MenuResult{Days: []MenuDay{{Weekday: 1, Dishes: []string{"甲"}}, {Weekday: 1, Dishes: []string{"乙"}}}}
	if normalizeMenu(&duplicate, week) == nil {
		t.Fatal("不能接受重复星期")
	}
}

func TestScheduleValidatesDifferentWeekdayTimes(t *testing.T) {
	a, b, c, d := "09:05", "09:45", "08:35", "09:15"
	result := ScheduleResult{Slots: []ScheduleSlot{
		{Weekday: 1, Title: "第一节", Start: &a, End: &b},
		{Weekday: 2, Title: "第一节", Start: &c, End: &d},
	}, Lessons: []ScheduleLesson{{Weekday: 1, Slot: "第一节", Subject: "语文"}}}
	if err := validateSchedule(result); err != nil {
		t.Fatal(err)
	}
	result.Slots[0].End = &c
	if validateSchedule(result) == nil {
		t.Fatal("不能接受结束早于开始")
	}
}
