# 安卓端工程（android/）构建说明

> 面向：在本目录写代码的人。只讲「怎么编译、为什么这么配、踩过什么坑」。
> 上层方案与环境背景见 `../docs/android/安卓端Kuikly方案.md` 与 `../docs/android/安卓端环境搭建记录.md`。

## 一、已验证的工具链组合

| 组件 | 版本 / 位置 | 说明 |
|---|---|---|
| JDK | 17（`/volume3/DeepSeekHarness/jdk-17`） | Kuikly 与 AGP 7.4.2 都要求 17；系统默认 java 是 25，不能直接用 |
| Android SDK | `/volume3/DeepSeekHarness/android-sdk` | platforms 30/32/33/34/37、build-tools 30.0.3 与 37.0.0 |
| Gradle | 8.11.1（wrapper，走腾讯云镜像下载） | 见第四节坑 1、坑 4 |
| AGP | 7.4.2 | 官方 Kuikly 示例工程同版本 |
| Kotlin | 2.1.21 | 与 Kuikly 构件后缀 `-2.1.21` 对应 |
| KSP | 2.1.21-2.0.1 | 生成页面注册表（`KuiklyCoreEntry.kt`） |
| Kuikly | `2.17.0-2.1.21`（已发布构件） | core / compose / core-annotations / core-ksp / core-render-android 五件同版本 |
| compileSdk / minSdk / targetSdk | 34 / 24 / 32 | 跟官方 demo 一致 |

依赖一律用**已发布构件**（`com.tencent.kuikly-open:*`），不把 Kuikly 源码拷进仓库：源码拷贝会在本地仓库里形成分叉，官方修复拿不到。

## 二、构建命令

```bash
cd android
JAVA_HOME=/volume3/DeepSeekHarness/jdk-17 GRADLE_USER_HOME=/volume3/DeepSeekHarness/gradle-home \
  setsid nohup bash gradlew :app:assembleDebug --no-daemon --no-watch-fs \
  > /tmp/infra-build.log 2>&1 < /dev/null &
```

三点必须照做：

- `JAVA_HOME` 与 `gradle.properties` 的 `org.gradle.java.home` **两个都要配**：前者决定启动 Gradle 的 launcher JVM，后者只决定 daemon/build JVM。
- `setsid nohup`：普通 `nohup &` 起的构建会被会话清理连带杀掉。
- `GRADLE_USER_HOME` 指到 `/volume3`：默认的 `$HOME/.gradle` 在 `/volume1`（本机只剩几百 MB，装不下 1.8G 依赖缓存）。

判断是否真在跑：`ps aux | grep java`（Gradle 进程名是 `java`，不是 `gradle`）。

首次构建约 9 分钟（含下载依赖），依赖齐了之后增量约 1 分钟。

## 三、目录职责

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties   # 工程与插件版本
├── gradle/maven-mirror.init.gradle                              # 上游仓库改写腾讯镜像（gradlew 自动 -I 注入）
├── gradlew / gradle/wrapper/                                     # wrapper（8.11.1）
├── local.properties                                              # sdk.dir，不入库
├── app/                                                          # 安卓外壳：Activity / 清单 / 图标
└── shared/src/commonMain/kotlin/
    ├── theme/          设计令牌、语义色、间距圆角（T2）
    ├── ui/components/  原子组件（T4）
    ├── ui/blocks/      业务块（T5）
    ├── ui/screens/     页面编排（T6）
    └── data/           模型、API 客户端、仓库（T3）
```

写页面时用 Kuikly 的两套 API 都能编译（已在 2.17.0 实测）：

- Kuikly DSL：`com.tencent.kuikly.core.pager.Pager`（**不是** `core.base.Pager`）、`com.tencent.kuikly.core.base.ViewBuilder`、`com.tencent.kuikly.core.annotations.Page`
- Kuikly Compose（`com.tencent.kuikly.compose.*`）：`androidx.compose.runtime.staticCompositionLocalOf` 等来自 compose 构件的传递依赖，可直接用

`@Page("名字")` 的页面由 KSP 自动登记进 `shared/build/generated/ksp/android/androidDebug/kotlin/KuiklyCoreEntry.kt`，无需手工注册。

## 四、踩过的坑（按现象→根因→做法）

### 坑 1：下载 Gradle 发行包超时

现象：`gradlew` 第一行就 `IOException: Downloading from https://services.gradle.org/... failed: timeout`。
根因：`services.gradle.org` 会 307 跳到 `downloads.gradle.org`，本机访问该 CDN 读超时。
做法：`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 改腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-<ver>-bin.zip`（实测 4~6MB/s）。

### 坑 2：`Protocol message contained an invalid tag (zero)`

现象：配置 `:app` 阶段直接失败，堆栈落点是 Kotlin 脚本编译读 `.kotlin_module`（`JvmModuleProtoBuf$Module.parseFrom`）。
排查过程（避免重复）：把缓存里 282 个 `.kotlin_module`（jar 内 + 散落文件）全部按 protobuf 结构扫过，**没有一个是坏的**；清 `caches/7.6.3/kotlin-dsl`、`jars-9` 后重试同样报错 —— 说明问题不在缓存损坏。
根因：wrapper 沿用了 KuiklyUI 源码仓库的 Gradle **7.6.3**，它内置的脚本编译器是 Kotlin **1.7.10**，读不了 Kotlin **2.1.21** 编译出来的插件元数据。
做法：wrapper 换 **Gradle 8.11.1**（Kuikly 官方示例工程就是用 AGP 7.4.2 + Kotlin 2.1.21 + Gradle 8.11.1 这组），同一份工程配置立刻通过。

### 坑 3：`Inconsistent JVM-target compatibility detected`

现象：`:app:compileDebugKotlin` 报 Java 任务 1.8 与 Kotlin 任务 17 不一致。
根因：Kotlin 未显式指定 `jvmTarget` 时跟 JDK 走（17），而 `compileOptions` 写的是 1.8。
做法：`:app` 里 `kotlinOptions { jvmTarget = "1.8" }`，两处对齐。

### 坑 4：`$HOME/.gradle` 所在卷放不下依赖缓存

现象：默认缓存目录在 `/volume1`（可用仅数百 MB），依赖解压后约 1.8G。
做法：构建命令显式给 `GRADLE_USER_HOME=/volume3/DeepSeekHarness/gradle-home`。换机器时该目录可随意重指定，删掉重下即可。

### 坑 5：上游仓库解析慢/被拒

现象：解析依赖时长时间卡住（Gradle 插件门户与 Maven Central 直连不稳）。
做法：`gradle/maven-mirror.init.gradle` 由 `gradlew` 以 `-I` 注入，把 `plugins.gradle.org`、`repo.maven.apache.org`、`repo1.maven.org` 改写到腾讯镜像；`google()` 与自建仓库不动。

### 坑 6：单测输出里的中文全变成 `?`

**现象**：测试断言全绿，但控制台与 XML 里的中文（如分类名）全是 `?`，数字与 URL 正常。

**根因**：Gradle 测试跑在 fork 出的 JVM 里，其默认字符集取自环境 locale。本机未设 `LANG` 时 JDK 17 退回 ASCII，`PrintStream` 编码时把非 ASCII 替换成 `?`。改 `LANG` / `LC_ALL` 对**已经启动**的 JVM 无效。

**做法**：给 gradlew 加编码参数，测试 worker 会继承：

```
JDK_JAVA_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8' \
  JAVA_HOME=<jdk17> GRADLE_USER_HOME=<编译根>/gradle-home \
  bash gradlew :shared:testDebugUnitTest --no-daemon --no-watch-fs
```

**判据**：输出里出现 `?` 就说明编码没生效 —— 先怀疑编码，不要怀疑业务逻辑。

### 坑 7：并发补数据丢更新（静默 bug）

**现象**：并发给 N 个分类补图片张数，部分分类一直停在 0 或旧值。所有请求都 200，日志无异常。

**根因**：并发体里对共享字段做「读-改-写」：

```kotlin
cachedCategories = cachedCategories.map { /* 只改自己那一个 */ }
```

两个协程各自读到同一份旧列表、各自写回自己那份，后写的覆盖先写的。赋值本身原子，但读与写之间的窗口没人管。

**做法**：拆成两段 —— 探测阶段并发且**只读**（`probeCategoryCount()`），全部结果回来后**单点写**（`replaceCategories()` 一次性合并）。并补一条「按请求内容路由的假传输 + 并发数 4」的回归用例，断言每个分类都到位。

**判据**：并发体里出现对共享状态的写，先问「这个读-改-写会不会被另一个协程夹在中间」；能用「收集后合并」的就不要边算边写。

## 五、验收（四条，缺一不可）

```bash
# 1) 构建日志
grep "BUILD SUCCESSFUL" /tmp/infra-build.log

# 2) 产物
ls -l app/build/outputs/apk/debug/app-debug.apk

# 3) 清单可解析
/volume3/DeepSeekHarness/android-sdk/build-tools/37.0.0/aapt2 dump badging \
  app/build/outputs/apk/debug/app-debug.apk | grep -E "^package:|launchable-activity"

# 4) 签名有效
/volume3/DeepSeekHarness/android-sdk/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/debug/app-debug.apk
```

当前实测结果：包名 `com.eightfs.lumigather`、应用名 `拾光集`、入口 `com.eightfs.lumigather.MainActivity`、APK 约 9.3MB、V2 签名（Android Debug 证书）。
