// 安卓外壳模块：只放 Activity / 清单 / 图标这类「平台外壳」，业务 UI 全在 :shared。
// 这样以后换壳（比如加启动页、多个入口 Activity）不会碰到业务代码。
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// release 签名凭据从 keystore.properties 读（该文件与 keystore/*.jks 一并入库）。
// 为什么用 Properties 而不是写死在 build.gradle.kts：证书口令与「构建逻辑」是两件事，
// 分开后换证书只改一处，也不会把口令混进版本控制里的构建脚本。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { input -> load(input) }
    }
}

android {
    compileSdk = 34
    namespace = "com.eightfs.lumigather"

    defaultConfig {
        applicationId = "com.eightfs.lumigather"
        minSdk = 24
        // 32：Kuikly 渲染器与官方 demo 一致的 targetSdk，先跟随官方组合，后续要上架再单独评估
        targetSdk = 32
        // versionCode 取日期（MMDD）而非递增序号：装错版本时一眼能看出新旧，
        // 且不会因为忘记加一而出现「同 code 不同内容」的升级歧义。
        versionCode = 1127
        versionName = "0.0.3-1127"
    }

    // release 打包时跳过 lintVital。
    // 原因（实测报错原文）：本机 build-tools 是 37.0.0，而 AGP 7.4.2 的 lint 只接受整数版本号，
    // 解析时报 java.lang.NumberFormatException: For input string: "37.0" —— lint 进程自己崩了，
    // 与我们代码无关。lint 属静态检查、不是功能验证，不该阻断发布；
    // 需要静态检查时另用与 AGP 匹配的 build-tools 单独跑。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            // 必须用 rootProject.file：本文件在 app 模块下，裸 file() 会解析成 android/app/keystore/…
            // 而 keystore.properties 里的相对路径是相对 android/ 根写的。
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // 正式证书签名：证书一旦丢失或更换，已发布版本将无法升级（用户须卸载重装）。
            // 证书与口令见 android/keystore* 两处，务必长期保留。
            signingConfig = signingConfigs.getByName("release")
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
