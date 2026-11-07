#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source_apk=${1:?请提供已构建的 APK 路径}
output_dir="$project_root/artifacts"
output_apk="$output_dir/慢慢-测试版.apk"
android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}

apk_signer=""
for candidate in "$android_sdk"/build-tools/*/apksigner; do
    if [[ -x "$candidate" ]]; then apk_signer=$candidate; fi
done
if [[ -z "$apk_signer" ]]; then
    printf '%s\n' '找不到 Android apksigner，未更新测试包。' >&2
    exit 1
fi
apk_inspector="$(dirname "$apk_signer")/aapt"
if [[ ! -x "$apk_inspector" ]]; then
    printf '%s\n' '找不到 Android aapt，未更新测试包。' >&2
    exit 1
fi

fingerprint() {
    local details digest
    details=$("$apk_signer" verify --print-certs "$1") || return 1
    digest=$(printf '%s\n' "$details" | awk -F ': ' '/Signer #1 certificate SHA-256 digest/ { print $2; exit }')
    [[ "$digest" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
    printf '%s' "$digest"
}

identity() {
    "$apk_inspector" dump badging "$1" | awk -F "'" 'NR == 1 && $1 == "package: name=" && $3 == " versionCode=" { print $2, $4 }'
}

if ! new_fingerprint=$(fingerprint "$source_apk"); then
    printf '%s\n' '新测试包签名校验失败，未更新已有安装包。' >&2
    exit 1
fi
new_identity=$(identity "$source_apk")
read -r new_package new_version <<< "$new_identity"
if [[ "$new_package" != "app.manman.teacher" || ! "$new_version" =~ ^[0-9]+$ ]]; then
    printf '%s\n' '新测试包的应用标识或版本无效，未更新已有安装包。' >&2
    exit 1
fi
if [[ -f "$output_apk" ]]; then
    if ! old_fingerprint=$(fingerprint "$output_apk"); then
        printf '%s\n' '已有测试包签名校验失败，未覆盖原文件。' >&2
        exit 1
    fi
    if [[ "$new_fingerprint" != "$old_fingerprint" ]]; then
        printf '%s\n' '新旧测试包签名不一致，已停止更新，避免影响覆盖安装。' >&2
        exit 1
    fi
    old_identity=$(identity "$output_apk")
    read -r old_package old_version <<< "$old_identity"
    if [[ "$old_package" != "$new_package" || ! "$old_version" =~ ^[0-9]+$ ]] || (( new_version < old_version )); then
        printf '%s\n' '新测试包的应用标识不一致或版本更旧，未覆盖已有安装包。' >&2
        exit 1
    fi
fi

mkdir -p "$output_dir"
temporary=$(mktemp "$output_dir/.manman-apk.XXXXXXXX")
trap 'if [[ -f "$temporary" ]]; then unlink "$temporary"; fi' EXIT
cp "$source_apk" "$temporary"
mv "$temporary" "$output_apk"
printf '最新版测试包：%s\n' "$output_apk"
