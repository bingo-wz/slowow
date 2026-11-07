package attachments

import (
	"bytes"
	"errors"
	"io"
	"os"
	"testing"
)

func TestPrivateImmutableUploads(t *testing.T) {
	store, err := New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	id := "a5abf451-028c-49ba-a3ac-c0f9656bd81d"
	image := append([]byte{137, 80, 78, 71, 13, 10, 26, 10}, bytes.Repeat([]byte{1}, 64)...)
	created, err := store.Put("owner-a", id, bytes.NewReader(image))
	if err != nil || !created {
		t.Fatalf("首次保存: %v", err)
	}
	created, err = store.Put("owner-a", id, bytes.NewReader(image))
	if err != nil || created {
		t.Fatalf("相同内容重试: %v", err)
	}
	other := append(append([]byte{}, image...), 42)
	_, err = store.Put("owner-a", id, bytes.NewReader(other))
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("不同内容不能覆盖原图: %v", err)
	}
	file, kind, err := store.Open("owner-a", id)
	if err != nil || kind != "image/png" {
		t.Fatalf("读取原图: %v", err)
	}
	defer file.Close()
	got, _ := io.ReadAll(file)
	if !bytes.Equal(got, image) {
		t.Fatal("图片内容发生改变")
	}
	_, _, err = store.Open("owner-b", id)
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("不能读取其他账号的图片: %v", err)
	}
	_, err = store.Put("owner-a", "../bad", bytes.NewReader(image))
	if !errors.Is(err, ErrInvalidID) {
		t.Fatalf("附件编号应拒绝路径: %v", err)
	}
	_, err = store.Put("owner-a", "a5abf451-028c-49ba-a3ac-c0f9656bd81e", bytes.NewBufferString("not an image"))
	if !errors.Is(err, ErrImage) {
		t.Fatalf("非图片应被拒绝: %v", err)
	}
	_, err = store.Put("owner-a", "a5abf451-028c-49ba-a3ac-c0f9656bd81f", bytes.NewReader(bytes.Repeat([]byte{1}, MaxSize+1)))
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("超大图片应被拒绝: %v", err)
	}
}
