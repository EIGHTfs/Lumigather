# 安卓端方案：Kuikly 重写手机 UI

> 状态：**规划中，未实现**。本文档只定方案与环境要求，不含代码。
> 后端零改动是本文的前提 —— 已验证，见第三节。

## 一、目标与边界

### 要解决的问题

现有网页按桌面鼠标交互设计：网格列数、点击目标尺寸、翻页方式、图片缩放都假定有鼠标和较大视口。在手机上表现为触控目标偏小、大图浏览不够顺手、缺少原生的左右滑动与双指缩放。

### 要做的事

用 Kotlin + Kuikly 重写一套专为触屏设计的手机端 UI，数据全部来自现有的 Lumigather 服务端 HTTP API。

### 不做的事

- 不改后端任何代码，不改网页版
- 不做 iOS / 鸿蒙 / macOS（Kuikly 支持，但本轮不覆盖）
- 不做登录、收藏、自动更新管理
- 不做完整 UI 信息架构（底部导航、搜索、压缩包浏览留到原型验证通过之后）

### 验收标准

三条全部通过才算原型成功：

1. 配置服务端地址后，能拉到目录列表并正常显示
2. 图片网格能分页加载、连续滚动不卡顿、不出现白块
3. 大图能全屏打开、左右滑动切换、双指缩放

## 二、为什么选 Kuikly

Kuikly 是腾讯开源的 Kotlin Multiplatform UI 框架（仓库 `Tencent-TDS/KuiklyUI`），一套代码可覆盖 Android、iOS、鸿蒙、Web、小程序、macOS，支持动态下发。

| 方案 | 工作量 | 得到 | 代价 |
|---|---|---|---|
| PWA（加 manifest） | 1 天 | 主屏安装、全屏 | 交互仍是网页，没解决触屏适配 |
| WebView 包壳 | 2–3 天 | 独立图标、可加原生分享 | UI 仍是网页，等于没重写 |
| **Kuikly** | 数周起 | 一套代码出多端、原生渲染、不发版可更新 UI | 要学 Kotlin、生态较新、组件要自己补 |
| 原生 Compose | 2–3 周 | 安卓体验最好 | 只覆盖安卓，将来 iOS 要重写 |

选 Kuikly 的前提是**确实要多端**。如果最终只要安卓，原生 Compose 更直接；如果只要「手机上能用」，PWA 或包壳更省。本方案按「多端 + 重写 UI」的目标立项。

## 三、后端契约（已实测，可直接照此实现）

这一节是手机端的全部数据来源，已在本机启动服务实测确认。

### 3.1 鉴权：核心读接口免鉴权

未登录状态下实测，以下端点全部返回 HTTP 200：

| 端点 | 用途 | 实测 |
|---|---|---|
| `GET /api/status` | 运行状态（含 `ok`/`needsSetup`/`uptimeSec`/`version`/`port`） | 200 |
| `GET /api/lumigather` | 站点配置（标题、分类、目录、`fsRoot`、`imagesPerRow`） | 200 |
| `GET /api/directories` | 目录列表 | 200 |
| `GET /api/images` | 图片列表（分页） | 200 |

只有自动更新管理那组端点需要登录（4 个）。手机端不做该功能即无需处理鉴权。

### 3.2 认证：可用 token，不必依赖 cookie

`POST /api/login` 的响应体里带 `token` 字段，同时设置 HttpOnly cookie。网页端用 cookie，脚本类客户端用 token —— 原生端走 token 通道即可，不受 cookie 策略影响。

### 3.3 响应格式

```
GET /api/directories
→ {"root": "/", "items": ["config", "mnt", ...]}

GET /api/images?dir=<路径>&offset=0&limit=20
→ {"path": "/", "root": "/", "recursive": false,
   "count": 0, "total": 0, "offset": 0, "limit": 20,
   "returned": 0, "images": []}
```

分页字段齐全：`total` 给总数，`offset`/`limit` 给游标，`returned` 给本次实际条数（用于判断是否到底）。

### 3.4 媒体 URL

| 场景 | URL 形态 |
|---|---|
| 单张图片 | `/api/media?file=<URL 编码后的绝对路径>` |
| 压缩包内图片 | `/api/zip/img?path=<zip 路径>&file=<包内文件名>` |

都是标准 GET，原生图片加载库可直接使用。

### 3.5 页面注入（原生端需自行绕开）

网页版有两个服务端注入的全局变量：`__LUMIGATHER_FS_ROOT`、`__LUMIGATHER_IMAGES_PER_ROW`。它们的值都来自 `GET /api/lumigather` 的响应，原生端直接调该端点即可获得同等数据，不涉及任何服务端渲染逻辑。

网页版的 `server/public/fragments/` 目录下只有静态样式片段与 `@brand:xxx@` 形式的品牌占位符替换，不含动态数据。

### 3.6 前端已有的部署前缀处理

网页版 `api-client.js` 里有一个 `apiUrl()` 函数，用于在服务端部署于子路径时拼接前缀。原生端需实现等效逻辑：`baseUrl`（可含子路径）+ 接口路径。

## 四、环境要求

### 4.1 JDK：必须是 17

Kuikly 官方明确要求 JDK 17。Android Studio 2024.2.1 及以上版本默认 Gradle JDK 为 21，与工程配置不兼容，需手动切到 17。

对 JDK 安装的要求：

- 必须是完整 JDK（含 `javac`），不能是 JRE
- 架构与本机一致（本机 x86_64 Linux）
- 路径中含空格会带来额外转义麻烦，建议放在无空格目录
- 装好后需满足：`<JDK17根>/bin/java -version` 能输出 17.x

配置方式（两条都要，缺一不可）：

1. 工程根目录的 `gradle.properties` 写：
   ```
   org.gradle.java.home=<JDK17 根目录>
   ```
2. Android Studio 内：`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 选同一个 17

不要改系统默认 java。当前系统 `java` 是 25，改动会影响其他项目；用上面两条配置把工程限定在 17 即可。

### 4.2 Android SDK

本机已有：platforms `android-33` / `android-37`、build-tools `37.0.0`、platform-tools、cmdline-tools。需在工程根 `local.properties` 指定：

```
sdk.dir=<android-sdk 目录>
```

或设置环境变量 `ANDROID_HOME`。

### 4.3 磁盘与工程位置

一次 Kotlin Multiplatform 构建的 Gradle 缓存、依赖与产物通常需要数 GB。工程位置需满足：

- 有足够空闲空间（建议预留 8 GB 以上）
- 不要放在 SMB/CIFS 挂载盘上构建：Gradle 需要频繁 `chmod` 与大量小文件写入，CIFS 上会报 `Operation not permitted`，且 IO 极慢
- 若只能放在挂载盘，至少把 `GRADLE_USER_HOME` 指到本地盘，并预期构建时间显著变长

### 4.4 Kotlin 版本

Kuikly 仓库根目录按 `x.x.xx.gradle.kts` 提供多个 Kotlin 版本的 Gradle 配置，默认使用 Kotlin 2.1.21。原型阶段跟随默认版本，不自选。

## 五、工程结构规划

```
Lumigather-Android/
├── gradle.properties          # org.gradle.java.home 指向 JDK17
├── local.properties           # sdk.dir 指向 Android SDK（不入库）
├── androidApp/                # 安卓外壳（Activity、清单、图标）
└── shared/                    # Kuikly 业务界面（Kotlin + Kuikly DSL）
    ├── api/                   # HTTP 客户端：baseUrl、四个 GET、媒体 URL 拼接
    ├── model/                 # 数据模型：目录、图片项、分页结果
    ├── ui/                    # 页面：目录列表、图片网格、大图查看
    └── theme/                 # 配色、字号、间距（对齐网页版品牌）
```

工程骨架优先取自 Kuikly 官方仓库的 `androidApp` 与示例模块，不自行臆造 Gradle 配置 —— 官方配置是唯一保证可编译的组合。

## 六、原型执行顺序

| 阶段 | 做什么 | 提交信息 |
|---|---|---|
| 一 骨架 | 取官方 androidApp 结构，JDK17 + SDK 配好，先编译出一个能装到手机的空 APK | `chore: 工程骨架与工具链跑通` |
| 二 连通 | HTTP 客户端 + baseUrl 配置页；调 `/api/directories`，把目录列表显示出来 | `feat: 目录列表` |
| 三 网格 | 调 `/api/images` 分页 + `/api/media` 懒加载，实现图片网格 | `feat: 图片网格` |
| 四 大图 | 全屏查看、左右滑切换、双指缩放 | `feat: 大图浏览` |
| 五 收尾 | 真机跑通、记录踩坑、写「能不能上」的结论 | `docs: 原型结论` |

每个阶段都要在手机上实际跑过再进下一阶段，不攒到最后一起验。

## 七、风险

| 风险 | 可能性 | 缓解 |
|---|---|---|
| JDK 版本不对导致编译失败 | 高 | 按 4.1 两条配置都做，先跑通空 APK 再写业务 |
| 磁盘不足导致构建中途失败 | 高 | 按 4.3 定位置，先确认空间够再开始 |
| Kuikly 无现成的图片缩放/懒加载组件 | 中 | 这是原型的重点验证项，早做早暴露，别等 UI 全写完 |
| 挂在 CIFS 上构建报权限错误 | 中 | 工程与 `GRADLE_USER_HOME` 都放本地盘 |
| 官方仓库含全平台模块，IDE sync 慢 | 中 | 只保留 androidApp + 业务模块，或提取最小结构 |

## 八、待验证项

动手后需确认，方案里不写死：

1. Kuikly 依赖的 Gradle 坐标与 Kotlin 版本的确切组合
2. 官方 `androidApp` 是否可直接改造为业务工程，还是需提取最小结构自建
3. 图片懒加载与手势缩放在 Kuikly 中的实现路径（有无官方或社区组件）
4. 真机上大图解码的内存占用（NAS 上的图可能很大，需确认是否需要降采样）

## 九、后续路线

原型三关通过后，再按优先级推进：

1. 完整信息架构：底部导航（分类 / 目录 / 设置）、搜索、最近浏览
2. 压缩包浏览：`/api/zip/scan` + `/api/zip/img`
3. GIF：`/api/gif`
4. 收藏：`/api/favorites`
5. 多端扩展：iOS、鸿蒙（Kuikly 一套代码，届时主要工作量在平台外壳与手势差异）
