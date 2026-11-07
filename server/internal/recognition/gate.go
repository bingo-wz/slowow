package recognition

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

var ErrNotBound = errors.New("识别服务未绑定当前账号")
var ErrInvalidCode = errors.New("授权码无效")
var ErrClaimRate = errors.New("授权尝试过于频繁")
var claimCodePattern = regexp.MustCompile(`^[0-9]{8}$`)

type Gate struct {
	pairHashFile string
	ownerFile    string
	mu           sync.Mutex
	lastAttempt  time.Time
	attemptDay   string
	attempts     int
}

func NewGate(pairHashFile, ownerFile string) (*Gate, error) {
	if pairHashFile == "" || ownerFile == "" {
		return nil, errors.New("识别账号绑定配置不完整")
	}
	return &Gate{pairHashFile: pairHashFile, ownerFile: ownerFile}, nil
}

func (g *Gate) Allowed(owner string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.allowed(owner)
}

func (g *Gate) allowed(owner string) bool {
	expected := ownerHash(owner)
	for _, path := range g.ownerFiles() {
		stored, err := os.ReadFile(path)
		if err == nil && subtle.ConstantTimeCompare([]byte(strings.TrimSpace(string(stored))), []byte(expected)) == 1 {
			return true
		}
	}
	return false
}

func (g *Gate) ownerFiles() []string {
	return []string{g.ownerFile, g.ownerFile + ".2"}
}

func (g *Gate) Claim(owner, code string, now time.Time) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.allowed(owner) {
		return nil
	}
	freeSlot := ""
	for _, path := range g.ownerFiles() {
		if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
			if freeSlot == "" {
				freeSlot = path
			}
		} else if err != nil {
			return ErrUnavailable
		}
	}
	if freeSlot == "" {
		return ErrNotBound
	}
	day := now.In(time.FixedZone("China", 8*60*60)).Format("2006-01-02")
	if g.attemptDay != day {
		g.attemptDay, g.attempts = day, 0
	}
	if g.attempts >= 10 || (!g.lastAttempt.IsZero() && now.Sub(g.lastAttempt) < 5*time.Second) {
		return ErrClaimRate
	}
	g.attempts++
	g.lastAttempt = now
	if !claimCodePattern.MatchString(code) {
		return ErrInvalidCode
	}
	stored, err := os.ReadFile(g.pairHashFile)
	if err != nil {
		return ErrUnavailable
	}
	actual := sha256.Sum256([]byte(code))
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(string(stored))), []byte(hex.EncodeToString(actual[:]))) != 1 {
		return ErrInvalidCode
	}
	if err := os.MkdirAll(filepath.Dir(g.ownerFile), 0700); err != nil {
		return ErrUnavailable
	}
	tmp, err := os.CreateTemp(filepath.Dir(g.ownerFile), ".owner-")
	if err != nil {
		return ErrUnavailable
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()
	if _, err = tmp.WriteString(ownerHash(owner)); err != nil {
		return ErrUnavailable
	}
	if err = tmp.Sync(); err != nil {
		return ErrUnavailable
	}
	if err = os.Link(tmp.Name(), freeSlot); err != nil {
		return ErrUnavailable
	}
	return nil
}

func ownerHash(owner string) string {
	value := sha256.Sum256([]byte(owner))
	return hex.EncodeToString(value[:])
}
