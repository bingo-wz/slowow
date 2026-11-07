package store

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"app.manman/server/internal/model"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrOperationReused = errors.New("operation ID reused with different content")

type ConflictError struct{ Current *model.Record }

func (e *ConflictError) Error() string { return "record version conflict" }

type Store interface {
	Apply(ctx context.Context, owner string, op model.Operation) (model.Record, error)
	Pull(ctx context.Context, owner string, after int64, limit int) (model.Page, error)
}

type Postgres struct{ pool *pgxpool.Pool }

func Open(ctx context.Context, databaseURL string) (*Postgres, error) {
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, err
	}
	config.MaxConns = 4
	config.MinConns = 0
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, err
	}
	var bypass bool
	err = pool.QueryRow(ctx, `SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user`).Scan(&bypass)
	if err != nil || bypass {
		pool.Close()
		if err != nil {
			return nil, fmt.Errorf("check database role: %w", err)
		}
		return nil, errors.New("database role must not bypass row-level security")
	}
	var protectedTables int
	err = pool.QueryRow(ctx, `SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
		WHERE n.nspname='public' AND c.relname IN ('manman_user_state','manman_records','manman_operations')
		AND c.relrowsecurity AND c.relforcerowsecurity`).Scan(&protectedTables)
	if err != nil || protectedTables != 3 {
		pool.Close()
		return nil, errors.New("database row-level security is not fully enabled")
	}
	result := &Postgres{pool: pool}
	tx, err := result.begin(ctx, "manman-startup-check")
	if err != nil {
		pool.Close()
		return nil, fmt.Errorf("check row-level security context: %w", err)
	}
	var recordCount int
	err = tx.QueryRow(ctx, `SELECT count(*) FROM manman_records`).Scan(&recordCount)
	_ = tx.Rollback(ctx)
	if err != nil || recordCount != 0 {
		pool.Close()
		if err != nil {
			return nil, fmt.Errorf("check database schema: %w", err)
		}
		return nil, errors.New("row-level security check failed")
	}
	return result, nil
}

func (p *Postgres) Close() { p.pool.Close() }

func (p *Postgres) begin(ctx context.Context, owner string) (pgx.Tx, error) {
	if owner == "" {
		return nil, errors.New("empty owner")
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, `SELECT set_config('app.user_id', $1, true)`, owner); err != nil {
		_ = tx.Rollback(ctx)
		return nil, err
	}
	return tx, nil
}

func (p *Postgres) Apply(ctx context.Context, owner string, op model.Operation) (model.Record, error) {
	var zero model.Record
	var payload any
	decoder := json.NewDecoder(bytes.NewReader(op.Payload))
	decoder.UseNumber()
	if err := decoder.Decode(&payload); err != nil {
		return zero, err
	}
	canonicalPayload, err := json.Marshal(payload)
	if err != nil {
		return zero, err
	}
	op.Payload = canonicalPayload
	canonical, err := json.Marshal(op)
	if err != nil {
		return zero, err
	}
	digest := sha256.Sum256(canonical)
	hash := hex.EncodeToString(digest[:])
	tx, err := p.begin(ctx, owner)
	if err != nil {
		return zero, err
	}
	defer tx.Rollback(ctx)

	if _, err = tx.Exec(ctx, `INSERT INTO manman_user_state(owner_id) VALUES($1) ON CONFLICT DO NOTHING`, owner); err != nil {
		return zero, err
	}
	var lastSequence int64
	if err = tx.QueryRow(ctx, `SELECT last_sequence FROM manman_user_state WHERE owner_id=$1 FOR UPDATE`, owner).Scan(&lastSequence); err != nil {
		return zero, err
	}

	var priorHash string
	var priorResponse []byte
	err = tx.QueryRow(ctx, `SELECT request_hash, response FROM manman_operations WHERE owner_id=$1 AND operation_id=$2`, owner, op.OperationID).Scan(&priorHash, &priorResponse)
	if err == nil {
		if priorHash != hash {
			return zero, ErrOperationReused
		}
		if err = json.Unmarshal(priorResponse, &zero); err != nil {
			return zero, err
		}
		return zero, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return zero, err
	}

	var current model.Record
	err = tx.QueryRow(ctx, `SELECT kind, record_id, payload, deleted, version, sequence, updated_at_ms
        FROM manman_records WHERE owner_id=$1 AND kind=$2 AND record_id=$3 FOR UPDATE`, owner, op.Kind, op.ID).
		Scan(&current.Kind, &current.ID, &current.Payload, &current.Deleted, &current.Version, &current.Sequence, &current.UpdatedAtMs)
	exists := err == nil
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return zero, err
	}
	if (!exists && op.BaseVersion != 0) || (exists && current.Version != op.BaseVersion) {
		if exists {
			return zero, &ConflictError{Current: &current}
		}
		return zero, &ConflictError{}
	}

	next := lastSequence + 1
	version := int64(1)
	if exists {
		version = current.Version + 1
	}
	result := model.Record{
		Kind: op.Kind, ID: op.ID, Payload: op.Payload, Deleted: op.Deleted,
		Version: version, Sequence: next, UpdatedAtMs: time.Now().UnixMilli(),
	}
	if _, err = tx.Exec(ctx, `UPDATE manman_user_state SET last_sequence=$2 WHERE owner_id=$1`, owner, next); err != nil {
		return zero, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO manman_records(owner_id, kind, record_id, payload, deleted, version, sequence, updated_at_ms)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8)
        ON CONFLICT(owner_id,kind,record_id) DO UPDATE SET payload=EXCLUDED.payload, deleted=EXCLUDED.deleted,
        version=EXCLUDED.version, sequence=EXCLUDED.sequence, updated_at_ms=EXCLUDED.updated_at_ms`,
		owner, op.Kind, op.ID, op.Payload, op.Deleted, version, next, result.UpdatedAtMs)
	if err != nil {
		return zero, err
	}
	response, err := json.Marshal(result)
	if err != nil {
		return zero, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO manman_operations(owner_id, operation_id, request_hash, response) VALUES($1,$2,$3,$4)`, owner, op.OperationID, hash, response); err != nil {
		return zero, err
	}
	if err = tx.Commit(ctx); err != nil {
		return zero, err
	}
	return result, nil
}

func (p *Postgres) Pull(ctx context.Context, owner string, after int64, limit int) (model.Page, error) {
	result := model.Page{Records: []model.Record{}, NextCursor: after}
	tx, err := p.begin(ctx, owner)
	if err != nil {
		return result, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT kind, record_id, payload, deleted, version, sequence, updated_at_ms
        FROM manman_records WHERE owner_id=$1 AND sequence>$2 ORDER BY sequence LIMIT $3`, owner, after, limit)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var record model.Record
		if err := rows.Scan(&record.Kind, &record.ID, &record.Payload, &record.Deleted, &record.Version, &record.Sequence, &record.UpdatedAtMs); err != nil {
			rows.Close()
			return result, err
		}
		result.Records = append(result.Records, record)
		result.NextCursor = record.Sequence
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	if err = tx.Commit(ctx); err != nil {
		return result, err
	}
	return result, nil
}
