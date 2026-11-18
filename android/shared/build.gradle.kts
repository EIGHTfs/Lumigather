// 业务层模块：所有 Kuikly 页面、主题、数据层都长在这里。
// 只配 Android 目标（iOS/鸿蒙/macOS 本轮不做），因此 source set 只有 commonMain / androidMain。
//
// 依赖全部走已发布构件（官方 QuickStart 的接入方式），不把 Kuikly 源码拷进本仓库：
// 拷贝源码会让第三方代码在本地仓库里形成分叉，官方修 bug 也拿不到。
// 版本规则 = <Kuikly版本>-<Kotlin版本>；core / compose / core-annotations / core-ksp /
// core-render-android 必须严格同版本，混用会出现渲染层与业务层协议不一致。
plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("com.tencent.kuikly-open.kuikly")
}

val KUIKLY_VERSION = "2.17.0-2.1.21"

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // core：Kuikly 基础能力（Pager/ViewBuilder/attr 等 DSL）
                implementation("com.tencent.kuikly-open:core:$KUIKLY_VERSION")
                // compose：Kuikly 的 Compose 风格 API（CompositionLocal 等在它里面）
                implementation("com.tencent.kuikly-open:compose:$KUIKLY_VERSION")
                // core-annotations：@Page 等注解，供 KSP 生成页面注册表
                implementation("com.tencent.kuikly-open:core-annotations:$KUIKLY_VERSION")
            }
        }
        val androidMain by getting {
            dependencies {
                // 渲染器放 androidMain 并 api 出去：:app 依赖本模块后就能拿到渲染层，无需重复声明
                api("com.tencent.kuikly-open:core-render-android:$KUIKLY_VERSION")
            }
        }
    }
}

dependencies {
    // KSP 处理器只在编译期用（生成页面注册表），不进产物
    compileOnly("com.tencent.kuikly-open:core-ksp:$KUIKLY_VERSION") {
        add("kspAndroid", this)
    }
}

android {
    namespace = "com.eightfs.lumigather.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
