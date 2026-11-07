//go:build !linux

package recognition

// 开发环境保留解析超时，线上 Linux 另外限制进程内存。
func limitSpreadsheetWorker() {}
