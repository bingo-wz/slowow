package recognition

import (
	"os"
	"strings"
	"testing"

	"github.com/xuri/excelize/v2"
)

func TestLegacySpreadsheetRunsInSeparateProcess(t *testing.T) {
	data, err := os.ReadFile("testdata/fictional.xls")
	if err != nil {
		t.Fatal(err)
	}
	result, err := Spreadsheet("xls", data)
	if err != nil || !strings.Contains(result, "星期一") || !strings.Contains(result, "语文") {
		t.Fatalf("旧版表格读取失败: %s %v", result, err)
	}
}

func TestSpreadsheetReadsCellsAndMergeRanges(t *testing.T) {
	book := excelize.NewFile()
	defer book.Close()
	if err := book.SetCellValue("Sheet1", "A1", "周一"); err != nil {
		t.Fatal(err)
	}
	if err := book.SetCellValue("Sheet1", "B2", "语文"); err != nil {
		t.Fatal(err)
	}
	if err := book.MergeCell("Sheet1", "A1", "B1"); err != nil {
		t.Fatal(err)
	}
	data, err := book.WriteToBuffer()
	if err != nil {
		t.Fatal(err)
	}
	result, err := Spreadsheet("xlsx", data.Bytes())
	if err != nil || !strings.Contains(result, "周一") || !strings.Contains(result, "语文") || !strings.Contains(result, "A1:B1") {
		t.Fatalf("表格未正确提取: %s %v", result, err)
	}
	if _, err := Spreadsheet("xls", []byte("不是表格")); err == nil {
		t.Fatal("应拒绝无效文件")
	}
}

func TestSpreadsheetRejectsExcessiveRows(t *testing.T) {
	book := excelize.NewFile()
	defer book.Close()
	if err := book.SetCellValue("Sheet1", "A201", "超限"); err != nil {
		t.Fatal(err)
	}
	data, err := book.WriteToBuffer()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Spreadsheet("xlsx", data.Bytes()); err == nil {
		t.Fatal("超限表格应拒绝")
	}
}
