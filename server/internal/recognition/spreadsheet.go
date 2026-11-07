package recognition

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/extrame/xls"
	"github.com/xuri/excelize/v2"
)

var ErrInvalidSpreadsheet = errors.New("表格格式无效或超过识别范围")

func init() {
	if os.Getenv("MANMAN_XLS_WORKER") != "1" {
		return
	}
	limitSpreadsheetWorker()
	data, err := io.ReadAll(io.LimitReader(os.Stdin, (8<<20)+1))
	if err != nil {
		os.Exit(1)
	}
	result, err := spreadsheetDirect("xls", data)
	if err != nil {
		os.Exit(1)
	}
	_, err = io.WriteString(os.Stdout, result)
	if err != nil {
		os.Exit(1)
	}
	os.Exit(0)
}

// 表格只提取单元格与合并区域，不执行公式或宏。
func Spreadsheet(kind string, data []byte) (result string, err error) {
	if kind == "xls" {
		if len(data) == 0 || len(data) > 8<<20 {
			return "", ErrInvalidSpreadsheet
		}
		path, err := os.Executable()
		if err != nil {
			return "", ErrInvalidSpreadsheet
		}
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer cancel()
		worker := exec.CommandContext(ctx, path)
		worker.Env = []string{"MANMAN_XLS_WORKER=1", "GOMEMLIMIT=96MiB"}
		worker.Stdin = bytes.NewReader(data)
		output, err := worker.Output()
		if err != nil || len(output) == 0 || len(output) > 30000 {
			return "", ErrInvalidSpreadsheet
		}
		return string(output), nil
	}
	return spreadsheetDirect(kind, data)
}

func spreadsheetDirect(kind string, data []byte) (result string, err error) {
	defer func() {
		if recover() != nil {
			result = ""
			err = ErrInvalidSpreadsheet
		}
	}()
	if len(data) == 0 || len(data) > 8<<20 {
		return "", ErrInvalidSpreadsheet
	}
	var out strings.Builder
	addRow := func(index int, cells []string) error {
		if index > 200 || len(cells) > 40 {
			return ErrInvalidSpreadsheet
		}
		for i, cell := range cells {
			if utf8.RuneCountInString(cell) > 500 {
				return ErrInvalidSpreadsheet
			}
			cell = strings.ReplaceAll(strings.ReplaceAll(strings.TrimSpace(cell), "\n", " "), "\t", " ")
			if cell != "" {
				fmt.Fprintf(&out, "[%d,%d]%s ", index, i+1, cell)
			}
		}
		out.WriteByte('\n')
		if out.Len() > 30000 {
			return ErrInvalidSpreadsheet
		}
		return nil
	}
	switch kind {
	case "xlsx":
		f, e := excelize.OpenReader(bytes.NewReader(data), excelize.Options{UnzipSizeLimit: 16 << 20, UnzipXMLSizeLimit: 16 << 20})
		if e != nil {
			return "", ErrInvalidSpreadsheet
		}
		defer f.Close()
		sheets := f.GetSheetList()
		if len(sheets) == 0 || len(sheets) > 5 {
			return "", ErrInvalidSpreadsheet
		}
		for _, name := range sheets {
			if utf8.RuneCountInString(name) > 100 {
				return "", ErrInvalidSpreadsheet
			}
			fmt.Fprintf(&out, "工作表：%s\n", name)
			merged, e := f.GetMergeCells(name)
			if e != nil || len(merged) > 400 {
				return "", ErrInvalidSpreadsheet
			}
			for _, cell := range merged {
				fmt.Fprintf(&out, "合并区域 %s:%s\n", cell.GetStartAxis(), cell.GetEndAxis())
			}
			rows, e := f.Rows(name)
			if e != nil {
				return "", ErrInvalidSpreadsheet
			}
			row := 0
			for rows.Next() {
				row++
				if row > 200 {
					rows.Close()
					return "", ErrInvalidSpreadsheet
				}
				cells, e := rows.Columns()
				if e != nil {
					rows.Close()
					return "", ErrInvalidSpreadsheet
				}
				if e = addRow(row, cells); e != nil {
					rows.Close()
					return "", e
				}
			}
			if rows.Error() != nil {
				rows.Close()
				return "", ErrInvalidSpreadsheet
			}
			rows.Close()
		}
	case "xls":
		book, e := xls.OpenReader(bytes.NewReader(data), "utf-8")
		if e != nil || book == nil || book.NumSheets() == 0 || book.NumSheets() > 5 {
			return "", ErrInvalidSpreadsheet
		}
		for i := 0; i < book.NumSheets(); i++ {
			sheet := book.GetSheet(i)
			if sheet == nil || sheet.MaxRow >= 200 {
				return "", ErrInvalidSpreadsheet
			}
			fmt.Fprintf(&out, "工作表：%s\n", sheet.Name)
			for row := 0; row <= int(sheet.MaxRow); row++ {
				item := sheet.Row(row)
				if item == nil {
					continue
				}
				if item.LastCol() > 40 {
					return "", ErrInvalidSpreadsheet
				}
				cells := make([]string, item.LastCol())
				for col := range cells {
					cells[col] = item.Col(col)
				}
				if e = addRow(row+1, cells); e != nil {
					return "", e
				}
			}
		}
	default:
		return "", ErrInvalidSpreadsheet
	}
	if strings.TrimSpace(out.String()) == "" {
		return "", ErrInvalidSpreadsheet
	}
	return out.String(), nil
}
