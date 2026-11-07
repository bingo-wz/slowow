package auth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

var ErrUnauthenticated = errors.New("unauthenticated")
var ErrUnavailable = errors.New("authentication unavailable")

type Verifier interface {
	Verify(ctx context.Context, token string) (string, error)
}

type CloudBase struct {
	BaseURL string
	Client  *http.Client
}

func NewCloudBase(envID string) (*CloudBase, error) {
	if envID == "" || strings.ContainsAny(envID, "/:?#@ ") {
		return nil, errors.New("invalid CloudBase environment ID")
	}
	return &CloudBase{
		BaseURL: fmt.Sprintf("https://%s.api.tcloudbasegateway.com", envID),
		Client: &http.Client{
			Timeout: 8 * time.Second,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

func (c *CloudBase) Verify(ctx context.Context, token string) (string, error) {
	if token == "" || len(token) > 8192 {
		return "", ErrUnauthenticated
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+"/auth/v1/user/me", nil)
	if err != nil {
		return "", ErrUnavailable
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	resp, err := c.Client.Do(req)
	if err != nil {
		log.Print("身份校验诊断：无法连接 CloudBase")
		return "", ErrUnavailable
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 && resp.StatusCode < 500 {
		var failure struct {
			Error       string `json:"error"`
			Description string `json:"error_description"`
		}
		_ = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&failure)
		reason := failure.Error
		switch reason {
		case "failed_precondition", "invalid_argument", "unauthenticated", "permission_denied", "resource_exhausted", "not_found":
		default:
			reason = "other"
		}
		log.Printf("身份校验诊断：CloudBase 状态=%d，类型=%s", resp.StatusCode, reason)
		description := strings.ToLower(failure.Description)
		invalidToken := reason == "failed_precondition" &&
			(strings.Contains(description, "malformed jwt") || strings.Contains(description, "token is expired") || strings.Contains(description, "token has expired"))
		if resp.StatusCode == http.StatusUnauthorized || reason == "unauthenticated" || invalidToken {
			return "", ErrUnauthenticated
		}
		return "", ErrUnavailable
	}
	if resp.StatusCode != http.StatusOK {
		log.Printf("身份校验诊断：CloudBase 状态=%d", resp.StatusCode)
		return "", ErrUnavailable
	}
	var profile struct {
		Sub    string `json:"sub"`
		Status string `json:"status"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&profile); err != nil {
		log.Print("身份校验诊断：CloudBase 响应格式无效")
		return "", ErrUnavailable
	}
	if profile.Sub == "" || len(profile.Sub) > 256 {
		log.Print("身份校验诊断：CloudBase 响应缺少有效用户标识")
		return "", ErrUnavailable
	}
	// status 在 CloudBase 接口中是可选字段；成功获取用户信息本身要求有效的 access_token。
	if profile.Status != "" && profile.Status != "ACTIVE" {
		status := "other"
		switch profile.Status {
		case "", "ACTIVE", "DISABLED", "BLOCKED":
			status = profile.Status
		}
		log.Printf("身份校验诊断：账号状态=%s", status)
		return "", ErrUnauthenticated
	}
	return profile.Sub, nil
}
