#!/bin/sh
# 仅在当前终端启用“慢慢”的 Android 开发环境。
MANMAN_JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null) || {
  printf '%s\n' '未找到 JDK 21，请先安装。' >&2
  return 1 2>/dev/null || exit 1
}
export JAVA_HOME="$MANMAN_JAVA_HOME"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export MANMAN_BUILD_ROOT="${MANMAN_BUILD_ROOT:-$HOME/Library/Caches/manman-build}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/.local/share/manman-tools/gradle-9.4.1/bin:$PATH"
unset MANMAN_JAVA_HOME
