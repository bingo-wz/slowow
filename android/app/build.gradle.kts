import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun configuredEndpoint(name: String): String {
    val value = providers.environmentVariable(name).orNull ?: localConfig.getProperty(name)
        ?: error("请在 android/local.properties 或环境变量中配置 $name")
    require(value.matches(Regex("^https://[A-Za-z0-9.-]+(?::[0-9]+)?$"))) { "$name 必须为 HTTPS 地址" }
    return value
}
val syncEndpoint = configuredEndpoint("MANMAN_SYNC_BASE_URL")
val authEndpoint = configuredEndpoint("MANMAN_AUTH_BASE_URL")

// 构建产物放到本机缓存，避免同步盘生成重复的中间文件。
providers.environmentVariable("MANMAN_BUILD_ROOT").orNull?.let { root ->
    layout.buildDirectory.set(file("$root/android-app"))
}

android {
    namespace = "app.manman.teacher"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.manman.teacher"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SYNC_BASE_URL", "\"$syncEndpoint\"")
        buildConfigField("String", "AUTH_BASE_URL", "\"$authEndpoint\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 每次成功构建测试包后，使用同一文件名供手机覆盖安装。
tasks.configureEach {
    if (name == "assembleDebug") {
        doLast {
            if (providers.environmentVariable("MANMAN_SKIP_APK_EXPORT").orNull == "1") {
                logger.lifecycle("本次为开发验证，暂不更新固定安装包。")
            } else {
                val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
                val exporter = rootProject.projectDir.parentFile.resolve("tools/export-test-apk.sh")
                val process = ProcessBuilder("bash", exporter.absolutePath, apk.absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val result = process.waitFor()
                check(result == 0) { "测试包导出失败，已有安装包未更新。$output" }
                logger.lifecycle(output)
            }
        }
    }
}
