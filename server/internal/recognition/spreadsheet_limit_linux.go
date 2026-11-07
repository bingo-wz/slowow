//go:build linux

package recognition

import "syscall"

// 限制旧版表格解析进程的内存，避免异常文件影响主服务。
func limitSpreadsheetWorker() {
	limits := []struct {
		resource int
		bytes    uint64
	}{
		{syscall.RLIMIT_AS, 2 << 30},
		{syscall.RLIMIT_DATA, 160 << 20},
	}
	for _, limit := range limits {
		if err := syscall.Setrlimit(limit.resource, &syscall.Rlimit{Cur: limit.bytes, Max: limit.bytes}); err != nil {
			panic("无法限制表格解析进程")
		}
	}
}
