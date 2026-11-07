package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"app.manman/server/internal/api"
	"app.manman/server/internal/attachments"
	"app.manman/server/internal/auth"
	"app.manman/server/internal/recognition"
	"app.manman/server/internal/store"
)

func main() {
	databaseURL := os.Getenv("MANMAN_DATABASE_URL")
	envID := os.Getenv("MANMAN_CLOUDBASE_ENV")
	if databaseURL == "" || envID == "" {
		log.Fatal("缺少数据库或身份认证环境配置")
	}
	verifier, err := auth.NewCloudBase(envID)
	if err != nil {
		log.Fatal("身份认证环境配置无效")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	repository, err := store.Open(ctx, databaseURL)
	cancel()
	if err != nil {
		log.Fatal("数据库连接或权限检查失败")
	}
	defer repository.Close()
	attachmentDir := os.Getenv("MANMAN_ATTACHMENTS_DIR")
	if attachmentDir == "" {
		attachmentDir = "/var/lib/manman/attachments"
	}
	files, err := attachments.New(attachmentDir)
	if err != nil {
		log.Fatal("附件存储目录不可用")
	}

	var recognizer recognition.Service
	var gate *recognition.Gate
	if keyFile := os.Getenv("MANMAN_MIMO_KEY_FILE"); keyFile != "" {
		endpoint := os.Getenv("MANMAN_MIMO_ENDPOINT")
		if endpoint == "" {
			endpoint = "https://api.xiaomimimo.com/v1/chat/completions"
		}
		model := os.Getenv("MANMAN_MIMO_MODEL")
		if model == "" {
			model = "mimo-v2.6-flash"
		}
		pairFile := os.Getenv("MANMAN_MIMO_PAIR_HASH_FILE")
		if pairFile != "" {
			recognizer, err = recognition.New(endpoint, model, keyFile)
			if err == nil {
				gate, err = recognition.NewGate(pairFile, "/var/lib/manman/mimo-owner")
			}
		}
		if pairFile == "" || err != nil {
			recognizer, gate = nil, nil
			log.Print("识别服务配置不完整，手动功能仍可使用")
		}
	}

	address := os.Getenv("MANMAN_LISTEN")
	if address == "" {
		address = "127.0.0.1:8088"
	}
	server := &http.Server{
		Addr: address, Handler: api.New(verifier, repository, files, recognizer, gate),
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 90 * time.Second,
		WriteTimeout: 140 * time.Second, IdleTimeout: 60 * time.Second,
		MaxHeaderBytes: 16 << 10,
	}
	done := make(chan os.Signal, 1)
	signal.Notify(done, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-done
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	log.Printf("慢慢服务正在监听 %s", address)
	if err = server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal("服务启动失败")
	}
}
