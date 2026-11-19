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
                // 协程：数据层对 UI 暴露 suspend 接口（挂起而不是阻塞 UI 线程），
                // 传输层用 Dispatchers.IO 跑阻塞调用。1.8.1 与 Kuikly 传递依赖的版本一致，
                // 且本机 Gradle 缓存里已有，不会引入新的下载与版本协商。
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val androidMain by getting {
            dependencies {
                // 渲染器放 androidMain 并 api 出去：:app 依赖本模块后就能拿到渲染层，无需重复声明
                api("com.tencent.kuikly-open:core-render-android:$KUIKLY_VERSION")
                // HTTP 客户端：OkHttp 是纯 JVM 库，放 androidMain 不参与 KMP 变体解析，
                // 与 Kuikly 2.17.0-2.1.21 / AGP 7.4.2 之间没有 metadata 协商风险。
                // 4.10.0 是本机 Gradle 缓存里已有的版本（Kuikly 传递依赖带入），无需额外下载。
                implementation("com.squareup.okhttp3:okhttp:4.10.0")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                // 数据层的解析/编码/错误映射都是纯逻辑，用普通 JVM 单测就能覆盖，不需要设备。
                // kotlin-test 会按当前 target 自动选 JUnit4 变体，junit 显式声明是为了版本确定。
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
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
