// 工程根构建脚本：只声明插件版本，实际应用在子模块里。
// 版本组合取自 KuiklyUI 官方仓库（AGP 7.4.2 + Kotlin 2.1.21 + KSP 2.1.21-2.0.1），
// 该组合在官方仓库里已验证可编译，不要单独升级其中某一个。
//
// Kuikly 构件版本规则 = <Kuikly版本>-<Kotlin版本>，当前 2.17.0-2.1.21。
// 改版本时下面 classpath 与 shared/build.gradle.kts 的 KUIKLY_VERSION 必须同时改，四处构件
// （core / compose / core-annotations / core-ksp / core-render-android）必须严格同版本。
plugins {
    id("com.android.application") version "7.4.2" apply false
    id("com.android.library") version "7.4.2" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.compose") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
}

buildscript {
    // buildscript classpath 的解析不受 settings 里 pluginManagement 仓库的影响，必须自带仓库
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
    dependencies {
        // Kuikly Gradle 插件：负责页面注册产物、本地 JS 打包等；Android-only 工程也会被 apply
        classpath("com.tencent.kuikly-open:core-gradle-plugin:2.17.0-2.1.21")
    }
}
