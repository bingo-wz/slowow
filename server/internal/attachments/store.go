package attachments

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

const MaxSize = 8 << 20
const MaxOwnerBytes = 512 << 20

var (
	ErrInvalidID = errors.New("无效的附件编号")
	ErrTooLarge  = errors.New("图片超过大小限制")
	ErrImage     = errors.New("不支持的图片格式")
	ErrConflict  = errors.New("附件编号已被其他文件使用")
	ErrQuota     = errors.New("附件空间已满")
	uuidPattern  = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
)

type Store struct {
	root string
	mu   sync.Mutex
}

func New(root string) (*Store, error) {
	if root == "" {
		return nil, errors.New("附件目录未配置")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		return nil, err
	}
	return &Store{root: root}, nil
}

func (s *Store) path(owner, id string) (string, error) {
	if !uuidPattern.MatchString(id) {
		return "", ErrInvalidID
	}
	key := sha256.Sum256([]byte(owner))
	return filepath.Join(s.root, hex.EncodeToString(key[:]), id), nil
}

func (s *Store) Put(owner, id string, source io.Reader) (bool, error) {
	path, err := s.path(owner, id)
	if err != nil {
		return false, err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return false, err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".upload-")
	if err != nil {
		return false, err
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()
	hash := sha256.New()
	size, err := io.Copy(io.MultiWriter(tmp, hash), io.LimitReader(source, MaxSize+1))
	if err != nil {
		return false, err
	}
	if size > MaxSize {
		return false, ErrTooLarge
	}
	if _, err = tmp.Seek(0, io.SeekStart); err != nil {
		return false, err
	}
	head := make([]byte, 512)
	n, readErr := tmp.Read(head)
	if readErr != nil && readErr != io.EOF {
		return false, readErr
	}
	if !supported(http.DetectContentType(head[:n])) {
		return false, ErrImage
	}
	if err = tmp.Sync(); err != nil {
		return false, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	existing, err := os.Open(path)
	if err == nil {
		defer existing.Close()
		oldHash := sha256.New()
		if _, err = io.Copy(oldHash, existing); err != nil {
			return false, err
		}
		if !equalHash(hash.Sum(nil), oldHash.Sum(nil)) {
			return false, ErrConflict
		}
		return false, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return false, err
	}
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		return false, err
	}
	var used int64
	for _, entry := range entries {
		if entry.IsDir() || strings.HasPrefix(entry.Name(), ".") {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return false, err
		}
		used += info.Size()
	}
	if used+size > MaxOwnerBytes {
		return false, ErrQuota
	}
	if err = os.Link(tmp.Name(), path); err != nil {
		return false, err
	}
	return true, nil
}

func (s *Store) Open(owner, id string) (*os.File, string, error) {
	path, err := s.path(owner, id)
	if err != nil {
		return nil, "", err
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, "", err
	}
	head := make([]byte, 512)
	n, err := file.Read(head)
	if err != nil && err != io.EOF {
		file.Close()
		return nil, "", err
	}
	kind := http.DetectContentType(head[:n])
	if !supported(kind) {
		file.Close()
		return nil, "", ErrImage
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return nil, "", err
	}
	return file, kind, nil
}

func supported(kind string) bool {
	return kind == "image/jpeg" || kind == "image/png" || kind == "image/webp"
}
func equalHash(a, b []byte) bool {
	return len(a) == len(b) && hex.EncodeToString(a) == hex.EncodeToString(b)
}
