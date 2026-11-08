package main

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"time"

	"app.manman/server/internal/recognition"
)

func main() {
	endpoint := os.Getenv("MANMAN_MIMO_ENDPOINT")
	model := os.Getenv("MANMAN_MIMO_MODEL")
	key := os.Getenv("MANMAN_MIMO_KEY_FILE")
	if endpoint == "" || model == "" || key == "" {
		fmt.Fprintln(os.Stderr, "识别服务配置不完整。")
		os.Exit(1)
	}
	client, err := recognition.New(endpoint, model, key)
	if err != nil {
		fmt.Fprintln(os.Stderr, "识别服务配置无效。")
		os.Exit(1)
	}
	picture := image.NewRGBA(image.Rect(0, 0, 128, 128))
	draw.Draw(picture, picture.Bounds(), &image.Uniform{C: color.White}, image.Point{}, draw.Src)
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, picture); err != nil {
		fmt.Fprintln(os.Stderr, "无法创建测试图片。")
		os.Exit(1)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 75*time.Second)
	defer cancel()
	result, err := client.Notice(ctx, "测试通知：请于2026年11月9日上午10点组织一次虚构班会。", []recognition.Image{{MIME: "image/png", Data: encoded.Bytes()}})
	if err != nil {
		fmt.Fprintln(os.Stderr, "识别调用未通过，请检查密钥、额度及模型权限。")
		os.Exit(1)
	}
	fmt.Printf("模拟通知和图片调用成功，返回 %d 条候选。\n", len(result.Items))
}
