# Lumigather · 拾光集

局域网里的 Mod 图片画廊：按目录浏览图片，压缩包内图片直出，序列帧自动合成 GIF。

## 功能

| 功能 | 做什么 | 入口 |
| --- | --- | --- |
| 目录浏览 | 按文件系统层级浏览图片，侧边栏目录树、面包屑导航 | 页面 |
| 分类筛选 | 按配置的分类过滤图片；支持搜索与排序（名称/数量/时间） | 页面 |
| 压缩包直出 | 不解压，直接显示 zip/7z/rar 内的图片 | `/api/zip/img` |
| 贴图翻转 | 压缩包含 ini 时，被 ini 声明的贴图自动 180° 翻转 | `/api/zip/img` |
| 序列帧合成 | 按包名间隔或 ini 的 `$speed` 换算帧率，合成 GIF | `/api/zip/gif`、`/api/gif` |
| 上传 | 上传图片到指定目录 | `/api/upload` |
| 收藏 | 收藏图片与目录，按分类归组 | `/api/favorites` |
| 多语言 | 中英切换，语言包随页面加载 | 页面 |
| 设置页 | 标题、图标、分类、目录、访问密码、自动更新 | 页面 |
| 自动更新 | 定时从 GitHub 拉取新版并重启 | `/api/auto-update/status` |
| 目录索引缓存 | 目录索引常驻内存、按 mtime 失效；命中率可查 | `/api/index-cache` |
| 结果分页 | 图片列表支持 `limit`/`offset`，默认返回全量 | `/api/images` |

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 运行时 | Node.js 18 或更高；零第三方依赖，不需要安装依赖 |
| 平台 | Linux / macOS / Windows |
| 外部工具 | `ffmpeg`（GIF 合成）、`7z`（压缩包读取）——运行时自动探测，不随程序分发 |

## 用法

### 启动

```bash
./start.sh start      # 后台启动
./start.sh status     # 查看运行状态
./start.sh restart    # 重启
./start.sh stop       # 停止
node server/boot.cjs  # 前台直接启动（调试用）
```

启动后访问 `http://<NAS-IP>:<端口>/`，端口见启动日志与设置页。

### 面板与设置页

| 操作 | 说明 |
| --- | --- |
| 分类按钮 | 切换分类；「全部」显示未分类内容 |
| 侧边栏 | 目录树，按目录跳转 |
| 搜索框 | 在当前分类内按文件名搜索 |
| 排序按钮 | 名称 / 数量 / 时间 |
| 设置 → 标题与图标 | 修改站点标题、favicon（Emoji 或图片 URL）；**设置值优先**，没设时用品牌默认（`BRAND_*` 环境变量 → `.env` → 内置默认）。Emoji 只用于顶栏图标，不写进 `<link rel="icon">` |
| 设置 → 分类 | 增删分类，设置颜色与显示名 |
| 设置 → 目录 | 添加图片根目录，指定所属分类 |
| 设置 → 密码 | 设置或修改访问密码（设置页需登录） |
| 设置 → 自动更新 | 开关、模式、间隔，可立即检查更新 |

### HTTP API

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/api/status` | GET | 服务状态与登录态 |
| `/api/lumigather` | GET/POST | 读取或保存站点配置（`/api/gallery` 为兼容别名） |
| `/api/directories` | GET | 列出已配置的根目录 |
| `/api/dir` | GET | 列单层目录与图片 |
| `/api/images` | GET | 列图片；`root`、`path`、`recursive`、`limit`、`offset` 均可空 |
| `/api/media` | GET | 输出单个图片文件 |
| `/api/zip/scan` | GET | 列目录下的压缩包 |
| `/api/zip/img` | GET | 输出压缩包内单张图片；`path`、`file` 必填 |
| `/api/zip/gif` | GET | 压缩包内序列帧合成 GIF；`path` 必填，`dur_ms`、`fps` 可空 |
| `/api/gif` | GET | 目录内序列帧合成 GIF；`dir` 必填，`dur_ms` 可空 |
| `/api/favorites` | GET/POST/DELETE | 收藏的读取、新增、删除 |
| `/api/upload` | POST | 上传图片；`targetDir` 可空（空则落到默认上传目录） |
| `/api/index-cache` | GET | 目录索引缓存统计（命中率、驻留字节） |
| `/api/auto-update/status` | GET | 自动更新状态 |
| `/api/auto-update/check` | POST | 立即检查更新 |
| `/api/auto-update/config` | POST | 保存自动更新配置 |
| `/api/auto-update/restart` | POST | 重启服务 |
| `/api/login`、`/api/logout` | POST | 登录、登出 |
| `/api/change-password` | POST | 设置或修改访问密码 |

```bash
# 列某个根目录下的全部图片（不传 limit = 全量）
curl 'http://<NAS-IP>:<端口>/api/images?root=/path/to/lumigather&recursive=true'

# 只取前 200 条（响应里的 count 仍是总数，returned 是本页数量）
curl 'http://<NAS-IP>:<端口>/api/images?root=/path/to/lumigather&recursive=true&limit=200&offset=0'
```

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [架构与数据流](https://github.com/EIGHTfs/Lumigather/blob/main/docs/架构与数据流.md) | 进程角色、请求链路、数据落点 |
| [目录与索引机制](https://github.com/EIGHTfs/Lumigather/blob/main/docs/目录与索引机制.md) | 索引何时建、如何失效、分页字段 |
| [索引缓存与命中率](https://github.com/EIGHTfs/Lumigather/blob/main/docs/索引缓存与命中率.md) | 缓存上限与开关、命中率怎么查 |
| [图片扫描与IO归因](https://github.com/EIGHTfs/Lumigather/blob/main/docs/图片扫描与IO归因.md) | 哪些请求读盘、分批执行、耗时归因 |
| [图片缓存与HTTP缓存头](https://github.com/EIGHTfs/Lumigather/blob/main/docs/图片缓存与HTTP缓存头.md) | 缓存头怎么发、怎么验证生效 |
| [压缩包与GIF规则](https://github.com/EIGHTfs/Lumigather/blob/main/docs/压缩包与GIF规则.md) | 直出、翻转判定、序列帧合成判定 |
| [根目录与排除规则](https://github.com/EIGHTfs/Lumigather/blob/main/docs/根目录与排除规则.md) | 黑名单模式、排除目录、扫描范围 |
| [部署与运维](https://github.com/EIGHTfs/Lumigather/blob/main/docs/部署与运维.md) | 首次部署、日常操作、迁移核对、已知限制 |
| [开发与排障](https://github.com/EIGHTfs/Lumigather/blob/main/docs/开发与排障.md) | 改代码自检步骤、常见现象处理 |
| [代码组织与文件归属](https://github.com/EIGHTfs/Lumigather/blob/main/docs/代码组织与文件归属.md) | 各目录职责、通用件与业务代码 |
| [索引与性能实现](https://github.com/EIGHTfs/Lumigather/blob/main/docs/索引与性能实现.md) | 性能优化的实现细节、实测数据与踩坑 |
| [版本记录](https://github.com/EIGHTfs/Lumigather/blob/main/docs/CHANGELOG.md) | 各版本改动 |
