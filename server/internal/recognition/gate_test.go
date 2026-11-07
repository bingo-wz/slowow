package recognition

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestGateBindsAtMostTwoAuthorizedAccounts(t *testing.T) {
	dir := t.TempDir()
	digest := sha256.Sum256([]byte("12345678"))
	hashFile := filepath.Join(dir, "pair.hash")
	if err := os.WriteFile(hashFile, []byte(hex.EncodeToString(digest[:])), 0600); err != nil {
		t.Fatal(err)
	}
	gate, err := NewGate(hashFile, filepath.Join(dir, "owner"))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 7, 1, 0, 0, 0, time.UTC)
	if gate.Allowed("a") {
		t.Fatal("未绑定时不能识别")
	}
	if err := gate.Claim("a", "00000000", now); !errors.Is(err, ErrInvalidCode) {
		t.Fatalf("错误授权码: %v", err)
	}
	if err := gate.Claim("a", "12345678", now.Add(time.Second)); !errors.Is(err, ErrClaimRate) {
		t.Fatalf("请求应限频: %v", err)
	}
	if err := gate.Claim("a", "12345678", now.Add(6*time.Second)); err != nil {
		t.Fatalf("正确授权码: %v", err)
	}
	if !gate.Allowed("a") || gate.Allowed("b") {
		t.Fatal("只有绑定账号可以识别")
	}
	if err := gate.Claim("b", "00000000", now.Add(time.Minute)); !errors.Is(err, ErrInvalidCode) {
		t.Fatalf("第二个账号也必须提供授权码: %v", err)
	}
	if err := gate.Claim("b", "12345678", now.Add(2*time.Minute)); err != nil {
		t.Fatalf("应允许第二个授权账号: %v", err)
	}
	if err := gate.Claim("c", "12345678", now.Add(3*time.Minute)); !errors.Is(err, ErrNotBound) {
		t.Fatalf("不能绑定第三个账号: %v", err)
	}
	if err := gate.Claim("a", "00000000", now.Add(4*time.Minute)); err != nil {
		t.Fatalf("同一账号重复绑定应保持成功: %v", err)
	}
	newGate, _ := NewGate(hashFile, filepath.Join(dir, "owner"))
	if !newGate.Allowed("a") || !newGate.Allowed("b") || newGate.Allowed("c") {
		t.Fatal("重启后绑定必须保留")
	}
}
