#!/usr/bin/env bash
set -euo pipefail

# 只用于全新安装；发现同名账号或数据库时停止，避免覆盖已有资源。
stage_dir=${1:?需要提供已经校验的临时目录}
env_id=${2:?需要提供 CloudBase 环境 ID}
case "$stage_dir" in /home/ubuntu/manman-stage.*) ;; *) printf '%s\n' '临时目录不符合预期。' >&2; exit 1;; esac
case "$env_id" in *[!a-zA-Z0-9-]*|'') printf '%s\n' '环境 ID 不符合预期。' >&2; exit 1;; esac

if getent passwd manman_app >/dev/null || getent group manman_app >/dev/null || [[ -e /etc/manman || -e /usr/local/bin/manman-server || -e /etc/systemd/system/manman.service ]]; then
    printf '%s\n' '同名系统资源已存在，停止以保护已有资源。' >&2
    exit 1
fi
existing_role=$(sudo -n -u postgres psql -Atq -d postgres -c "SELECT rolname FROM pg_roles WHERE rolname='manman_app'")
existing_database=$(sudo -n -u postgres psql -Atq -d postgres -c "SELECT datname FROM pg_database WHERE datname='manman'")
if [[ -n "$existing_role" || -n "$existing_database" ]]; then printf '%s\n' '数据库或角色已存在，停止以保护已有资源。' >&2; exit 1; fi

sudo -n useradd --system --user-group --home-dir /nonexistent --shell /usr/sbin/nologin manman_app
sudo -n -u postgres psql -v ON_ERROR_STOP=1 -d postgres -c 'CREATE ROLE manman_app LOGIN NOINHERIT NOBYPASSRLS'
sudo -n -u postgres createdb --owner=postgres manman
sudo -n -u postgres psql -v ON_ERROR_STOP=1 -d postgres -c 'REVOKE CONNECT ON DATABASE manman FROM PUBLIC'
sudo -n -u postgres psql -v ON_ERROR_STOP=1 -d postgres -c 'GRANT CONNECT ON DATABASE manman TO manman_app'
sudo -n -u postgres psql -v ON_ERROR_STOP=1 -d manman < "$stage_dir/001_initial.sql"

sudo -n install -d -o root -g manman_app -m 0750 /etc/manman
printf 'MANMAN_LISTEN=127.0.0.1:8088\nMANMAN_CLOUDBASE_ENV=%s\nMANMAN_DATABASE_URL=postgresql:///manman?host=/var/run/postgresql&user=manman_app\n' "$env_id" > "$stage_dir/server.env"
sudo -n install -o root -g manman_app -m 0640 "$stage_dir/server.env" /etc/manman/server.env
sudo -n install -o root -g root -m 0755 "$stage_dir/manman-server-linux-amd64" /usr/local/bin/manman-server
sudo -n install -o root -g root -m 0644 "$stage_dir/manman.service" /etc/systemd/system/manman.service
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now manman.service
sudo -n systemctl is-active manman.service
