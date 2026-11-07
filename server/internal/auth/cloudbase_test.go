package auth

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestCloudBaseVerifier(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/auth/v1/user/me" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		switch r.Header.Get("Authorization") {
		case "Bearer good":
			fmt.Fprint(w, `{"sub":"user-a","status":"ACTIVE"}`)
		case "Bearer no-status":
			fmt.Fprint(w, `{"sub":"user-a"}`)
		case "Bearer disabled":
			fmt.Fprint(w, `{"sub":"user-a","status":"DISABLED"}`)
		case "Bearer blocked":
			fmt.Fprint(w, `{"sub":"user-a","status":"BLOCKED"}`)
		case "Bearer missing-sub":
			fmt.Fprint(w, `{"status":"ACTIVE"}`)
		case "Bearer limited":
			w.WriteHeader(http.StatusTooManyRequests)
			fmt.Fprint(w, `{"error":"resource_exhausted"}`)
		case "Bearer malformed":
			w.WriteHeader(http.StatusBadRequest)
			fmt.Fprint(w, `{"error":"failed_precondition","error_description":"oidc: malformed jwt"}`)
		case "Bearer precondition":
			w.WriteHeader(http.StatusBadRequest)
			fmt.Fprint(w, `{"error":"failed_precondition","error_description":"configuration unavailable"}`)
		case "Bearer offline":
			w.WriteHeader(http.StatusServiceUnavailable)
		default:
			w.WriteHeader(http.StatusUnauthorized)
		}
	}))
	defer server.Close()
	verifier := &CloudBase{BaseURL: server.URL, Client: server.Client()}
	user, err := verifier.Verify(context.Background(), "good")
	if err != nil || user != "user-a" {
		t.Fatalf("expected verified user: %q %v", user, err)
	}
	user, err = verifier.Verify(context.Background(), "no-status")
	if err != nil || user != "user-a" {
		t.Fatalf("CloudBase 省略可选状态时应接受已验证用户: %q %v", user, err)
	}
	for _, token := range []string{"bad", "disabled", "blocked", "malformed", ""} {
		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnauthenticated) {
			t.Fatalf("token %q should be rejected: %v", token, err)
		}
	}
	_, err = verifier.Verify(context.Background(), "offline")
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("expected unavailable: %v", err)
	}
	for _, token := range []string{"missing-sub", "limited", "precondition"} {
		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnavailable) {
			t.Fatalf("服务异常不能误报登录过期: %q %v", token, err)
		}
	}
}

func TestVerifierDoesNotForwardTokensToRedirects(t *testing.T) {
	var reached atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		reached.Store(true)
		w.WriteHeader(http.StatusOK)
	}))
	defer target.Close()
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusFound)
	}))
	defer source.Close()
	verifier, err := NewCloudBase("test-env")
	if err != nil {
		t.Fatal(err)
	}
	verifier.BaseURL = source.URL
	if _, err = verifier.Verify(context.Background(), "test-token"); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("重定向应被拒绝: %v", err)
	}
	if reached.Load() {
		t.Fatal("身份凭证不应跟随重定向")
	}
}

func TestDiagnosticsExcludeCredentialsAndPrivateResponses(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprint(w, `{"error":"failed_precondition","error_description":"sensitive-description"}`)
	}))
	defer server.Close()
	var logs bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&logs)
	defer log.SetOutput(previous)
	verifier := &CloudBase{BaseURL: server.URL, Client: server.Client()}
	_, _ = verifier.Verify(context.Background(), "sensitive-token")
	if strings.Contains(logs.String(), "sensitive-token") || strings.Contains(logs.String(), "sensitive-description") {
		t.Fatal("诊断日志不能包含凭证或原始错误内容")
	}
	if !strings.Contains(logs.String(), "failed_precondition") {
		t.Fatal("应保留可定位的错误类别")
	}
}
