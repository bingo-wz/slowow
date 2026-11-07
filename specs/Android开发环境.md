# 慢慢：Android 开发环境

更新时间：2026-11-08

## 约定

- 使用已有的 JDK 21，不修改系统默认 JDK，也不覆盖其他项目的 Java 设置。
- Android SDK 安装在 `~/Library/Android/sdk`。
- Gradle 9.4.1 安装在 `~/.local/share/manman-tools/gradle-9.4.1`，用于初始化与验证；正式工程应使用项目自带的 Gradle Wrapper 固定构建版本。
- 通过项目内的 `tools/android-env.sh` 设置当前终端的 `JAVA_HOME`、`ANDROID_HOME` 和工具路径；不修改全局 shell 配置。
- 为避免“文稿”同步目录产生重复的 Gradle 中间文件，环境脚本将构建输出放在 `~/Library/Caches/manman-build`；未设置该变量的其他环境使用 Gradle 默认位置。
- 使用官方发布的 Apple Silicon 安装包，并核对 Android 命令行工具及 Gradle 安装包的官方 SHA-256 校验值。

已安装 Android SDK Platform 36 和 37、Build-Tools 36.0.0、Platform-Tools 37.0.1、NDK 28.2.13676358、Emulator 37.2.13 和 Android 16 ARM64 系统镜像。正式工程使用 Android Gradle Plugin 9.2.0、`compileSdk 37`、`targetSdk 36`、`minSdk 26`。虚拟设备名称为 `manman-api36`，按需启动，不需要时可以关闭。

## 在终端中启用

从项目根目录执行：

```sh
. ./tools/android-env.sh
java -version
gradle --version
adb version
```

环境变量只对当前终端及由其启动的进程生效，关闭终端后自然失效。Android Studio 不是命令行构建的前提；如果以后安装 Android Studio，需要在其 Gradle 设置中选择 JDK 21，并使用上述 SDK 路径。

构建前在未纳入版本管理的 `android/local.properties` 中设置业务和身份服务 HTTPS 地址，也可以设置同名环境变量：

```properties
MANMAN_SYNC_BASE_URL=https://sync.example.com
MANMAN_AUTH_BASE_URL=https://your-env.api.tcloudbasegateway.com
```

上述地址只是格式示例；实际环境以使用者自己的配置为准，密钥不能放入应用或仓库。

每次执行 `./gradlew :app:assembleDebug` 成功后，最新版会自动导出到项目根目录的 `artifacts/慢慢-测试版.apk`。导出会核对新旧安装包的签名、应用标识和版本；签名或标识不一致、版本更旧时保留旧包并报错。后续测试包交付使用这个固定路径。

开发中构建尚未完成验证的版本时，在命令前设置 `MANMAN_SKIP_APK_EXPORT=1`，避免提前替换用户可安装的固定文件；全部验证通过后，再正常构建生成交付包。

需要启动模拟器时，在已启用环境的终端执行：

```sh
emulator -avd manman-api36
```

## 验证方式

`android/` 是正式产品工程。已在 `manman-api36` 模拟器运行单元测试与设备测试，覆盖虚构的班级数据、午餐、课程、文字及图片分享、提醒、离线数据与 HTTPS 连接。开发者可在项目根目录执行：

```sh
. ./tools/android-env.sh
cd android
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

涉及真实服务器的证书测试默认跳过；需要联网验证时，附加 `-Pandroid.testInstrumentationRunnerArguments.testServer=1`。服务器或网络不稳定时该检查可能超时，不能将其当作离线单元测试。

已通过 NDK 编译独立的 ARM64 验证库，并检查去除调试信息前后的结果；Gradle 的原生库处理任务也已复核通过。安装 NDK 能提供这一构建能力，实际应用体积仍需对正式发布包测量。

小米手机的系统限制、通知和后台提醒行为，仍需在真机上单独测试。
