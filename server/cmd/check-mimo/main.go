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
	if len(os.Args) > 1 && (os.Args[1] == "schedule" || os.Args[1] == "schedule-image") {
		table := "虚构示例：周一第一节 09:05-09:45 语文；周二第一节 08:35-09:15 数学。"
		var images []recognition.Image
		if os.Args[1] == "schedule-image" {
			if len(os.Args) != 3 {
				os.Exit(1)
			}
			data, err := os.ReadFile(os.Args[2])
			if err != nil || len(data) > 1<<20 {
				os.Exit(1)
			}
			images = []recognition.Image{{MIME: "image/png", Data: data}}
			table = ""
		}
		result, err := client.Schedule(ctx, table, images)
		if err != nil {
			fmt.Fprintln(os.Stderr, "模拟课表识别失败。 ")
			os.Exit(1)
		}
		var monday, tuesday bool
		for _, slot := range result.Slots {
			if slot.Start == nil {
				continue
			}
			monday = monday || (slot.Weekday == 1 && *slot.Start == "09:05")
			tuesday = tuesday || (slot.Weekday == 2 && *slot.Start == "08:35")
		}
		if !monday || !tuesday || len(result.Lessons) < 2 {
			fmt.Fprintln(os.Stderr, "模拟课表识别未匹配两个星期的时间。 ")
			os.Exit(1)
		}
		fmt.Printf("模拟课表识别成功：%d 个作息、%d 项课程。\n", len(result.Slots), len(result.Lessons))
		return
	}
	if len(os.Args) == 3 && os.Args[1] == "menu-image" {
		data, err := os.ReadFile(os.Args[2])
		if err != nil || len(data) > 1<<20 {
			os.Exit(1)
		}
		week, _ := time.Parse("2006-01-02", "2026-11-09")
		result, err := client.Menu(ctx, week, []recognition.Image{{MIME: "image/png", Data: data}})
		if err != nil {
			fmt.Fprintln(os.Stderr, "模拟菜单图片识别失败。 ")
			os.Exit(1)
		}
		var monday, tuesday bool
		for _, day := range result.Days {
			monday = monday || day.Date == "2026-11-09"
			tuesday = tuesday || day.Date == "2026-11-10"
		}
		if !monday || !tuesday {
			fmt.Fprintln(os.Stderr, "模拟菜单未按星期映射。 ")
			os.Exit(1)
		}
		fmt.Printf("模拟菜单图片识别成功：%d 天。\n", len(result.Days))
		return
	}
	result, err := client.Notice(ctx, "测试通知：请于2026年11月9日上午10点组织一次虚构班会。", []recognition.Image{{MIME: "image/png", Data: encoded.Bytes()}})
	if err != nil {
		fmt.Fprintln(os.Stderr, "识别调用未通过，请检查密钥、额度及模型权限。")
		os.Exit(1)
	}
	fmt.Printf("模拟通知和图片调用成功，返回 %d 条候选。\n", len(result.Items))
}
