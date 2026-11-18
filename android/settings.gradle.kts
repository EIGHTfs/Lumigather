// 拾光集安卓端工程根配置。
// 模块划分：:app 是安卓外壳（Activity/清单/图标），:shared 是 Kuikly 业务层（theme/ui/data）。
// 仓库优先级：先 google/mavenCentral，再走腾讯镜像 —— 本机访问 Google Maven 与 Maven Central 直连可用，
// 但 Kuikly 自身的构件只发布在腾讯镜像上，镜像必须列在候选里。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

rootProject.name = "LumigatherAndroid"

include(":app")
include(":shared")
