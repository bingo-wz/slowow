package model

import "encoding/json"

type Operation struct {
	OperationID string          `json:"operationId"`
	Kind        string          `json:"kind"`
	ID          string          `json:"id"`
	BaseVersion int64           `json:"baseVersion"`
	Deleted     bool            `json:"deleted"`
	Payload     json.RawMessage `json:"payload"`
}

type Record struct {
	Kind        string          `json:"kind"`
	ID          string          `json:"id"`
	Payload     json.RawMessage `json:"payload"`
	Deleted     bool            `json:"deleted"`
	Version     int64           `json:"version"`
	Sequence    int64           `json:"sequence"`
	UpdatedAtMs int64           `json:"updatedAt"`
}

type Page struct {
	Records    []Record `json:"records"`
	NextCursor int64    `json:"nextCursor"`
}
