#!/usr/bin/env bash
set -euo pipefail
umask 077

backup_home=${MANMAN_BACKUP_HOME:-"$HOME/Library/Application Support/manman"}
identity=${MANMAN_BACKUP_IDENTITY:-"$backup_home/backup-identity.txt"}
backup_root=${MANMAN_BACKUP_ROOT:-"$backup_home/backups"}
server=${MANMAN_SSH_HOST:-manman-server}

command -v age >/dev/null || { printf '%s\n' '请先安装 age。' >&2; exit 1; }
command -v age-keygen >/dev/null || { printf '%s\n' '找不到 age-keygen。' >&2; exit 1; }
mkdir -p "$(dirname "$identity")" "$backup_root"
if [[ ! -e "$identity" ]]; then
    age-keygen -o "$identity" >/dev/null
    chmod 600 "$identity"
fi
recipient=$(age-keygen -y "$identity")
temporary=$(mktemp -d "$backup_root/.pending.XXXXXXXX")
trap 'rm -rf "$temporary"' EXIT

# 先取得数据库快照，再打包不可覆盖的附件，避免快照引用了未备份的图片。
ssh -o BatchMode=yes "$server" 'sudo -n -u postgres pg_dump --format=custom manman' \
    | age -r "$recipient" > "$temporary/database.dump.age"
ssh -o BatchMode=yes "$server" 'sudo -n tar -C /var/lib/manman -cf - attachments' \
    | age -r "$recipient" > "$temporary/attachments.tar.age"
(
    cd "$temporary"
    shasum -a 256 database.dump.age attachments.tar.age > SHA256SUMS
)
printf '创建时间：%s\n内容：数据库快照与私有附件，均使用 age 加密。\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" > "$temporary/说明.txt"
final="$backup_root/$(date '+%Y%m%d-%H%M%S')-${temporary##*.}"
mv "$temporary" "$final"
trap - EXIT
printf '备份已保存：%s\n' "$final"
printf '恢复密钥位置：%s\n' "$identity"
printf '%s\n' '请将恢复密钥另存到安全位置；丢失密钥将无法解密备份。'
