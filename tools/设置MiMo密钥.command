#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' '为慢慢设置小米官方 MiMo 密钥。密钥输入时不会显示，也不会写入聊天或应用安装包。'
printf '%s\n' '1. 小米官方按量计费 API Key' '2. 小米官方 Token Plan API Key'
read -r -p '请选择 1 或 2：' kind
case "$kind" in
    1) endpoint='https://api.xiaomimimo.com/v1/chat/completions' ;;
    2) endpoint='https://token-plan-cn.xiaomimimo.com/v1/chat/completions' ;;
    *) printf '%s\n' '未选择有效类型，未作任何修改。'; exit 1 ;;
esac

ssh -o BatchMode=yes manman-server 'sudo -n test ! -e /etc/manman/mimo.key && sudo -n test ! -e /etc/manman/mimo-pairing.sha256 && sudo -n test ! -e /var/lib/manman/mimo-owner && ! sudo -n grep -q "^MANMAN_MIMO_" /etc/manman/server.env' || {
    printf '%s\n' '服务器已有 MiMo 配置或无法连接，已停止以免覆盖现有密钥。' >&2
    exit 1
}
read -r -s -p '请粘贴 API Key，然后按回车：' mimo_secret
printf '\n'
if [[ "$kind" == 1 && "$mimo_secret" != sk-* ]] || [[ "$kind" == 2 && "$mimo_secret" != tp-* && "$mimo_secret" != ttp-* ]] ||
   [[ ! "$mimo_secret" =~ ^[A-Za-z0-9_-]+$ ]] || (( ${#mimo_secret} < 12 || ${#mimo_secret} > 1024 )); then
    unset mimo_secret
    printf '%s\n' '密钥格式与所选类型不一致，未作任何修改。' >&2
    exit 1
fi
printf '%s' "$mimo_secret" | ssh -o BatchMode=yes manman-server \
    'sudo -n install -o root -g manman_app -m 0640 /dev/stdin /etc/manman/mimo.key'
unset mimo_secret
pairing_code=$(python3 -c 'import secrets; print(f"{secrets.randbelow(100000000):08d}")')
pairing_hash=$(printf '%s' "$pairing_code" | shasum -a 256 | awk '{print $1}')
printf '%s' "$pairing_hash" | ssh -o BatchMode=yes manman-server \
    'sudo -n install -o root -g manman_app -m 0640 /dev/stdin /etc/manman/mimo-pairing.sha256'
unset pairing_hash

# 服务地址只允许脚本中列出的两个小米官方地址。
printf 'MANMAN_MIMO_KEY_FILE=/etc/manman/mimo.key\nMANMAN_MIMO_PAIR_HASH_FILE=/etc/manman/mimo-pairing.sha256\nMANMAN_MIMO_ENDPOINT=%s\nMANMAN_MIMO_MODEL=mimo-v2.6-flash\n' "$endpoint" \
    | ssh -o BatchMode=yes manman-server 'sudo -n tee -a /etc/manman/server.env >/dev/null && sudo -n systemctl restart manman.service && sudo -n systemctl is-active --quiet manman.service'
printf '\n%s\n' '密钥已安全保存到服务器。请在慢慢的“设置 → 绑定识别账号”中输入以下授权码，最多绑定两个账号：'
printf '%s\n' "$pairing_code"
unset pairing_code
printf '%s\n' '授权码只在应用里输入，不要发到聊天。完成后可回到对话告知进度。'
read -r -p '按回车关闭窗口。' _
