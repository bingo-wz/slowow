package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"app.manman/server/internal/model"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestPostgresIsolationIdempotencyAndConflict(t *testing.T) {
	dsn := os.Getenv("MANMAN_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("未配置独立的 PostgreSQL 测试数据库")
	}
	ctx := context.Background()
	repository, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()

	suffix := fmt.Sprint(time.Now().UnixNano())
	ownerA, ownerB := "teacher-a-"+suffix, "teacher-b-"+suffix
	first := model.Operation{OperationID: "op-1", Kind: "student", ID: "student-1", Payload: json.RawMessage(`{"name":"虚构学生甲","active":true}`)}
	saved, err := repository.Apply(ctx, ownerA, first)
	if err != nil || saved.Version != 1 || saved.Sequence != 1 {
		t.Fatalf("first save: %+v %v", saved, err)
	}

	retry := first
	retry.Payload = json.RawMessage(`{"active":true,"name":"虚构学生甲"}`)
	repeated, err := repository.Apply(ctx, ownerA, retry)
	if err != nil || repeated.Version != saved.Version || repeated.Sequence != saved.Sequence {
		t.Fatalf("idempotent retry: %+v %v", repeated, err)
	}
	retry.Payload = json.RawMessage(`{"name":"不同内容"}`)
	if _, err = repository.Apply(ctx, ownerA, retry); !errors.Is(err, ErrOperationReused) {
		t.Fatalf("expected operation reuse error, got %v", err)
	}

	page, err := repository.Pull(ctx, ownerB, 0, 10)
	if err != nil || len(page.Records) != 0 {
		t.Fatalf("other owner must see nothing: %+v %v", page, err)
	}
	other := first
	other.Payload = json.RawMessage(`{"name":"虚构学生丙","active":true}`)
	if _, err = repository.Apply(ctx, ownerB, other); err != nil {
		t.Fatalf("independent owner save: %v", err)
	}
	second := model.Operation{OperationID: "op-2", Kind: "student", ID: "student-1", BaseVersion: 1, Payload: json.RawMessage(`{"name":"虚构学生乙"}`)}
	updated, err := repository.Apply(ctx, ownerA, second)
	if err != nil || updated.Version != 2 || updated.Sequence != 2 {
		t.Fatalf("update: %+v %v", updated, err)
	}
	stale := model.Operation{OperationID: "op-3", Kind: "student", ID: "student-1", BaseVersion: 1, Payload: json.RawMessage(`{"name":"另一台设备"}`)}
	_, err = repository.Apply(ctx, ownerA, stale)
	var conflict *ConflictError
	if !errors.As(err, &conflict) || conflict.Current == nil || conflict.Current.Version != 2 {
		t.Fatalf("expected retained conflict: %v", err)
	}
	page, err = repository.Pull(ctx, ownerA, 0, 10)
	if err != nil || len(page.Records) != 1 || page.Records[0].Version != 2 {
		t.Fatalf("expected latest record: %+v %v", page, err)
	}
	page, err = repository.Pull(ctx, ownerB, 0, 10)
	if err != nil || len(page.Records) != 1 {
		t.Fatalf("other owner's separate record: %+v %v", page, err)
	}
	var otherData map[string]any
	if err = json.Unmarshal(page.Records[0].Payload, &otherData); err != nil || otherData["name"] != "虚构学生丙" {
		t.Fatalf("wrong owner's record: %+v %v", otherData, err)
	}

	results := make(chan error, 2)
	for _, operationID := range []string{"concurrent-1", "concurrent-2"} {
		go func(id string) {
			_, applyErr := repository.Apply(ctx, ownerA, model.Operation{
				OperationID: id, Kind: "student", ID: "student-1", BaseVersion: 2,
				Payload: json.RawMessage(`{"name":"并发编辑"}`),
			})
			results <- applyErr
		}(operationID)
	}
	successes, conflicts := 0, 0
	for range 2 {
		applyErr := <-results
		var concurrentConflict *ConflictError
		if applyErr == nil {
			successes++
		} else if errors.As(applyErr, &concurrentConflict) {
			conflicts++
		} else {
			t.Fatalf("unexpected concurrent error: %v", applyErr)
		}
	}
	if successes != 1 || conflicts != 1 {
		t.Fatalf("concurrent updates must preserve conflict: successes=%d conflicts=%d", successes, conflicts)
	}

	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	var count int
	if err = pool.QueryRow(ctx, `SELECT count(*) FROM manman_records`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("RLS must deny access without transaction identity: %d %v", count, err)
	}
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `SELECT set_config('app.user_id',$1,true)`, ownerA); err != nil {
		t.Fatal(err)
	}
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM manman_records WHERE owner_id=$1`, ownerA).Scan(&count); err != nil || count != 1 {
		t.Fatalf("RLS should allow own data: %d %v", count, err)
	}
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM manman_records WHERE owner_id=$1`, ownerB).Scan(&count); err != nil || count != 0 {
		t.Fatalf("RLS must reject other owner: %d %v", count, err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO manman_records(owner_id,kind,record_id,payload,version,sequence,updated_at_ms) VALUES($1,'student','forged','{}',1,1,0)`, ownerB)
	if err == nil {
		t.Fatal("RLS must reject writes for other owners")
	}
}
