// 安卓外壳模块：只放 Activity / 清单 / 图标这类「平台外壳」，业务 UI 全在 :shared。
// 这样以后换壳（比如加启动页、多个入口 Activity）不会碰到业务代码。
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    compileSdk = 34
    namespace = "com.eightfs.lumigather"

    defaultConfig {
        applicationId = "com.eightfs.lumigather"
        minSdk = 24
        // 32：Kuikly 渲染器与官方 demo 一致的 targetSdk，先跟随官方组合，后续要上架再单独评估
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // 骨架阶段 release 也临时用 debug 证书，保证 assembleRelease 能直接出包；
            // 正式签名证书在发布前单独配置
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Java 与 Kotlin 的 jvmTarget 必须一致，否则 AGP 8 之前的 Kotlin 任务会直接报
    // "Inconsistent JVM-target compatibility detected"（Kotlin 默认跟 JDK 走 17，Java 这边写的 1.8）。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Kuikly 渲染层的 .so 不能被 strip，否则运行时找不到符号
    packagingOptions {
        doNotStrip("**/*.so")
    }
}

dependencies {
    // 业务层（含 Kuikly 渲染器，通过 shared 的 api 传递过来）
    implementation(project(":shared"))

    // Kuikly 承载容器后续要用 AppCompatActivity，先把官方 demo 同版本的基础库备好
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("com.google.android.material:material:1.4.0")
}
