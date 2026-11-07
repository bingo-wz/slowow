#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' '如果授权码丢失，可在两个账号尚未绑定满时重新生成，已绑定账号不受影响。'
ssh -o BatchMode=yes manman-server 'sudo -n test -s /etc/manman/mimo.key && sudo -n test -s /etc/manman/mimo-pairing.sha256 && { sudo -n test ! -e /var/lib/manman/mimo-owner || sudo -n test ! -e /var/lib/manman/mimo-owner.2; }' || {
    printf '%s\n' '服务尚未设置、两个账号均已绑定或无法连接，已停止。' >&2
    exit 1
}
pairing_code=$(python3 -c 'import secrets; print(f"{secrets.randbelow(100000000):08d}")')
pairing_hash=$(printf '%s' "$pairing_code" | shasum -a 256 | awk '{print $1}')
printf '%s' "$pairing_hash" | ssh -o BatchMode=yes manman-server \
    '{ sudo -n test ! -e /var/lib/manman/mimo-owner || sudo -n test ! -e /var/lib/manman/mimo-owner.2; } && sudo -n install -o root -g manman_app -m 0640 /dev/stdin /etc/manman/mimo-pairing.sha256'
unset pairing_hash
printf '\n%s\n' '请在慢慢的“设置 → 绑定识别账号”中输入以下新授权码：'
printf '%s\n' "$pairing_code"
unset pairing_code
printf '%s\n' '授权码只在应用里输入，不要发到聊天。'
read -r -p '按回车关闭窗口。' _
