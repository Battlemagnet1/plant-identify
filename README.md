# Plant Identify Library

面向园林专业学生、园林园艺从业者、植物学学习者与植物调查人员的 **AI 多图植物识别 + 植物数字档案** Android 应用。

核心特色是**多图联合识别**：一次上传 1–5 张同一株植物的照片（整株 / 叶片 / 花 / 果实 / 树皮），由视觉模型综合所有视觉证据进行判断，而非对每张图独立识别后投票。

## 当前状态

**功能开发完成，已发布 v1.0.1。** `main` 可编译、可运行；单元测试 **452 用例**（226 × 2 个版本）全绿。

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 工程骨架、Compose、Material 3、Navigation、Room 三张表 | ✅ |
| 2 | CameraX、Photo Picker、多图选择与排序、本地图片保存 | ✅ |
| 3 | VisionProvider、多图联合识别、容错 JSON 解析 | ✅ |
| 4 | TextProvider、植物详细分析、识别结果页、低置信度处理 | ✅ |
| 5 | 植物档案、多次观察、详情、编辑、删除、搜索、筛选、归并 | ✅ |
| 6 | 统计、观察地点、HTML 导出、数据备份与恢复 | ✅ |
| — | 实机反馈的七项修复（定位可用性、大图查看、俗称与病虫害、报告折叠、编辑全开放） | ✅ |
| 7 | 性能优化、异常兜底、单元测试、双版本发布 | ✅ |
| — | **识别任务队列**：一批照片丢进队列、后台逐个识别、自动重试与僵尸自愈 | ✅ 完整版 |
| — | **数据清洗与去重**：回收站、六级重复判定、AI 复核、合并预览 | ✅ 完整版 |
| — | **压测三档**（100 / 1000 / 10000 株）与出包自检 | ✅ 见 [`Plant Identify - 压测报告.md`](Plant%20Identify%20-%20压测报告.md) |

## 下载与安装

到 [Releases](https://github.com/Battlemagnet1/plant-identify/releases) 下载 APK 直接安装（无需 Google Play）。

| 版本 | 包名 | 说明 |
|---|---|---|
| [v1.0.1-full](https://github.com/Battlemagnet1/plant-identify/releases/tag/v1.0.1-full) | `com.plantidentify.full` | **完整版，推荐**。含统计、识别任务、数据清洗、地点、导出与备份 |
| [v1.0.0-full](https://github.com/Battlemagnet1/plant-identify/releases/tag/v1.0.0-full) | `com.plantidentify` | 基础版。功能面自 1.0.0 起未变，从该页取 `plant-identify-base-v1.0.0.apk` |

- 要求 **Android 8.0（API 26）及以上**。
- **两个版本可以同时安装**（包名不同），档案数据也能通过备份包互相迁移。
- 签名证书 SHA-256：`95065ED8A06005DB8F6D8D6EB0403E1C94DAF0D5B0AF2D160C5C38643675D9F1`
- 装完**必须先配置 AI 服务**才能识别，见下文「如何配置 API」。

> 图文并茂的使用教程见 [`docs/Plant Identify Library 使用教程.pdf`](docs/)。

## 两个版本：基础版与完整版

同一个代码库产出两个可**同时安装、互不覆盖**的 APK：

| | 基础版 base | 完整版 full |
|---|---|---|
| applicationId | `com.plantidentify` | `com.plantidentify.full` |
| 应用显示名 | Plant Identify Library | Plant Identify Library（完整版） |
| 版本号 | `1.0.0` / code 1 | `1.0.1` / code 2 |
| 多图识别 · 植物百科 · 档案 · 搜索筛选 · 多次观察 · 归并 · 备注 | ✅ | ✅ |
| 统计数据（不同植物 / 观察次数 / 照片数） | — | ✅ |
| 识别任务队列（后台批量识别） | — | ✅ |
| 数据清洗与去重（回收站、AI 复核、合并预览） | — | ✅ |
| 观察地点（定位权限 + 地点记录与展示） | — | ✅ |
| HTML 导出 · 备份 · 恢复（数据管理页） | — | ✅ |
| 常用名称（俗称）与病虫害防治 | — | ✅ |
| 大图查看与保存到相册 | — | ✅ |
| 编辑页的照片增删 | — | ✅ |

**版本号各管各的**：两个包独立分发，各自的用户只会覆盖升级到自己那条线。
所以完整版加了功能就跳（`1.0.0` → `1.0.1`），基础版没加就不跳（仍是 `1.0.0`）。
新增版本时 `versionCode` 必须大于上一版，否则老用户升不上来 —— 那时唯一的症状是「装完还是旧版本」。

两者的**数据库结构、AI 请求、图片存储方式完全相同**，差异只在界面入口（由编译期常量 `FULL_EDITION` 控制，见 `app/src/main/java/com/plantidentify/AppEdition.kt`）。
因此两边的备份包可以互相迁移：基础版备份的数据，完整版能完整恢复，反之亦然。

> 基础版的 applicationId 与历史版本一致，装过旧版基础版的用户可以直接覆盖升级，不需要卸载。

## 如何导入项目

1. 安装 **JDK 17** 与 **Android SDK**（Platform 37 + Build-Tools）。
2. 克隆仓库并进入目录：

   ```bash
   git clone https://github.com/Battlemagnet1/plant-identify.git
   cd plant-identify
   ```

3. 告诉 Gradle SDK 在哪里 —— 在仓库根目录新建 `local.properties`（该文件已被 `.gitignore` 排除）：

   ```properties
   sdk.dir=C\:\\Users\\<你的用户名>\\Android\\Sdk
   ```

   > Windows 下**盘符后的冒号必须转义成 `\:`**，反斜杠写成 `\\`，否则 Gradle 会报找不到 SDK。

4. 用 Android Studio 打开仓库根目录即可（选择 "Open"，不要用 "Import Project"）。
   没有 Android Studio 也能开发，命令行足够，见下一节。

**不需要**任何密钥、签名文件或账号即可完成导入与调试构建。

## 如何运行

### 在设备或模拟器上运行调试包

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew installBaseDebug        # 装基础版
./gradlew installFullDebug        # 装完整版
```

也可以只出包再手动安装：

```bash
./gradlew assembleDebug           # 两个 flavor 的调试包一起出
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

### 首次使用

1. 打开应用 →「设置」→ 配置 AI 服务（**视觉识别**这一条是必须的，见下节）。
2. 回首页 →「添加植物」→ 拍照或从相册选择 1–5 张同一株植物的照片（建议含整株 + 特写，可选标注部位）。
3. 点「开始识别」，结果页可查看置信度、证据、候选物种，确认后保存为植物档案。
4. 想再补充这株植物的观察记录，从详情页进入并追加照片即可。

> **不配置 AI 服务就无法识别**，会提示「还没有配置 AI 服务」。
> 一步一步的图文教程见 [`docs/Plant Identify Library 使用教程.pdf`](docs/)。


### 位置功能（仅完整版）

进入「设置 → 数据管理」可以打开「记录观察地点」。开启后会在保存档案时记下拍摄地点，用于按地点筛选。
地点**只存在本机**，不会上传到任何服务器。拒绝定位权限不影响拍照、识别与建档。

## 如何配置 API

应用不内置任何 API Key，也不代理请求 —— 由你直接调用自己选择的服务商。

### 两条通道

应用要用两次 AI，各管一件事，**可以配成两家不同的服务商**：

| 通道 | 干什么 | 需要的模型 |
|---|---|---|
| **视觉识别服务** | 看 1–5 张照片，判断是什么植物 | 必须支持图片输入（视觉模型） |
| **文字分析服务** | 基于识别结果补齐百科字段（花期、养护、病虫害…） | 普通对话模型即可，**不让视觉模型猜知识** |

进「设置」→ 先选**预置服务商**（会自动填好 Base URL 与推荐模型名），再补上 API Key，
点「测试连接」通过后保存：

| 预置 | Base URL | 默认视觉模型 | 默认文字模型 |
|---|---|---|---|
| Qwen（阿里云百炼） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-vl-max` | `qwen-plus` |
| 豆包（火山方舟） | `https://ark.cn-beijing.volces.com/api/v3` | 需填接入点 ID | 需填接入点 ID |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o` | `gpt-4o-mini` |
| 自定义（OpenAI 兼容） | 自己填 | 自己填 | 自己填 |

**文字分析服务默认「与视觉识别共用」**——两家相同就什么都不用改；
想省钱（视觉用大模型、文字用小模型）或想分开计费，关掉这个开关单独填一次即可。

配置存放在本机的 DataStore 中，其中 API Key 使用 **Android Keystore 的 AES-256-GCM** 加密后存放。

> 卸载重装会清除 Keystore 中的密钥，即使 DataStore 里还留着密文也解不开（界面会退回「缺少 API Key」）。
> 换包、换设备后请重新配置一次。

### 如何配置 Base URL

Base URL 指 OpenAI 兼容接口的**根路径**，通常以 `/v1` 结尾，应用会在其后拼接 `/chat/completions` 与 `/models`。

| 服务商 | Base URL |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| 阿里云百炼（通义千问） | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 火山方舟（豆包） | `https://ark.cn-beijing.volces.com/api/v3` |
| 本地模拟服务（开发用） | `http://127.0.0.1:8899/v1` |

要点：

- **末尾的 `/v1` 或 `/v3` 不能省**，也不能多写路径段 —— 少了会得到 404，多了会得到 401 或 404。
- **必须是 `https://`**。Android 9 起默认禁止明文 HTTP，填 `http://` 会被系统拦截并提示「该地址使用明文 HTTP」。本地开发时可配合 `adb reverse` 使用 `127.0.0.1`。
- 自建服务的证书必须有效，自签证书会报「安全连接建立失败」。

### 如何配置模型

在「模型名」里填写服务商文档给出的**模型 ID 原文**，不要写产品名。

| 服务商 | 模型名示例 | 说明 |
|---|---|---|
| OpenAI | `gpt-4o` | 需支持图片输入 |
| 阿里云百炼 | `qwen-vl-max` | 带 `vl` 字样的是视觉模型 |
| 火山方舟 | `doubao-1.5-vision-pro` 或 `ep-` 开头的接入点 ID | 方舟上常需用控制台创建的接入点 ID |

- **必须使用支持图片输入的视觉模型**。填成纯文本模型会得到「该模型不接受图片输入」，应用会明确提示换一个视觉模型。
- **两条通道各填各的**：视觉通道必须是视觉模型；文字通道用普通对话模型就行（`qwen-plus`、`gpt-4o-mini` 这类更便宜）。
  若两处填了同一个模型，只要它同时支持图片与长文本，也完全没问题。
- 模型名写错会得到「模型名不正确，或该模型未在你的账号下开通」，应用会区分「模型名错」与「模型不支持图片」两种情形，按提示修正即可。

### 如何使用 OpenAI Compatible API

只要服务商实现了 OpenAI 的 `POST {baseUrl}/chat/completions`，即可直接使用；应用另用 `GET {baseUrl}/models` 做连通性测试。

支持的请求能力：

- 多图输入：`messages[].content` 中的 `image_url`，图片以 `data:image/jpeg;base64,...` 内联（上传前统一压缩到最长边 1536px、JPEG 质量 80，并已按 EXIF 纠正方向）
- 流式响应：`stream` 可选；应用两种都支持
- 响应解析：**不依赖 `response_format`**。应用要求模型直接输出 JSON，并在客户端做了完整容错 —— 剥离 markdown 围栏、括号配对扫描、键名归一化（含中文键名）、每字段逐级降级取值。因此对「JSON 输出不稳定」的兼容实现也能工作

因此多数国产 OpenAI 兼容端点可直接接入，无需改代码。若遇到不兼容的返回，应用会降级为展示原始文本而不是直接失败。

#### 本地联调（开发用）

仓库自带一个 OpenAI 兼容的模拟服务端，含 22 种响应模式，可模拟超时、401、429、非 JSON 返回等各类异常：

```bash
python tools/mock_ai_server.py --port 8899
adb reverse tcp:8899 tcp:8899         # 让设备能访问本机的 8899
curl -X POST "http://127.0.0.1:8899/control?mode=ok"   # 切换响应模式
```

Base URL 填 `http://127.0.0.1:8899/v1`，模型名随意（如 `mock-vl`），API Key 随意。

## 如何构建 APK

### 调试包

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/base/debug/app-base-debug.apk
#       app/build/outputs/apk/full/debug/app-full-debug.apk
```

### 发布包

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/base/release/app-base-release.apk
#       app/build/outputs/apk/full/release/app-full-release.apk
```

两个 flavor 的 APK 文件名自带 `base` / `full` 后缀，不会互相覆盖。

### 发布包签名

签名密钥与口令**存在仓库之外**，配置写在仓库根目录的 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

该文件不存在时 `assembleRelease` 会退化为产出**未签名**包，而不是让构建失败 —— 这样其他人 clone 下来无需任何密钥就能正常构建。

> 签名密钥一旦丢失，就无法再对已发布的应用做覆盖升级。请单独备份 `.jks` 文件与口令。

### 出包自检

签名包出好后跑一遍自检（检查 Android SDK 是否可定位、证书、签名方案版本、包名、版本号、显示名、是否 debuggable、包内是否误带密钥等）：

```bash
python tools/verify_release_apk.py --edition base
python tools/verify_release_apk.py --edition full
```

### 测试与静态检查

```bash
./gradlew test              # 单元测试（纯 JVM，秒级）
./gradlew lint              # Android Lint
```

> 注意不要写 `lintDebug` —— 引入两个 flavor 之后这个名字有歧义，
> Gradle 会直接报错。要单独跑某个版本用 `lintBaseDebug` / `lintFullDebug`。

## 如何使用应用

> 标题里标注「仅完整版」的功能，基础版没有入口 —— 代码与数据库两版一致，只是藏了界面入口。

### 如何批量识别（识别任务队列，仅完整版）

一株一株点「添加植物」太慢时，用任务队列把识别丢到后台。

1. 首页点「识别任务」→「新建任务」→ 从相册选 **1–5 张**照片。
2. 任务进队列后**在后台逐个识别** —— 可以退出应用、锁屏，回来时进度还在。
   列表上能看到每个任务的状态：排队中 / 识别中 / 分析中 / 失败 / 已取消。
3. 识别成功会直接写入档案。若结果与**已有档案**可能是同一种，它不会直接合并，
   而是**暂挂在那株名下**等你确认：
   - **「确实是同一种」** → 归到那一株，作为一次新的观察记录
   - **「拆分为新植物」** → 另建一株（同名但其实是不同个体时选这个）
4. 失败的任务可以「重试」；不想跑了的可以「取消任务」（已识别的部分不会写入档案）。

> 任务**串行执行**（并发 1）：既不把服务商的并发额度打满，也不让手机同时上传好几张大图。
> 应用被杀掉后重新打开会**自愈**掉僵尸任务，不会永远卡在「进行中」。

### 如何清理重复档案（数据清洗，仅完整版）

同一株植物记了两次、或同一张照片存了两遍，都会在这里被找出来。

「设置 → 数据清洗」有三个动作，代价差别很大：

| 动作 | 代价 | 什么时候用 |
|---|---|---|
| **开始检查** | 秒级，只看上次检查之后改过的档案 | 平时随手看一眼 |
| **全库深度检查** | 较慢，忽略游标把每一株都重新过一遍 | 大改过数据之后 |
| **AI 复核** | **会花钱**，调用文字模型 | 本地判不准的灰区候选 |

- 检查是**纯本地**的（算照片哈希、比名称、查坐标），只有点「AI 复核」才会联网花钱。
- 结论分六级：学名完全相同、中文名完全相同这类**本地就能拍板**的不会送给 AI —— 那是白花钱。
- 每条问题都写清了涉及哪几株、为什么可疑；点进去可逐字段对比再决定。
- **清洗只做提示，绝不自动改你的数据。** 合并与删除都要确认后才生效。
- 被合并或删除的档案进入**回收站**（「设置 → 回收站」），可以随时恢复。

### 如何导出 HTML

> 仅完整版。基础版没有数据管理页。

1. 「设置 → 数据管理」。
2. 在「导出 HTML」区块选择模式：

   | 模式 | 内容 | 适用 |
   |---|---|---|
   | 缩略图（默认） | 单文件 HTML，内嵌 1024px / JPEG 75 缩略图 | 分享、存档，体积可控 |
   | 原图内嵌 | 单文件 HTML，嵌入原图 base64 | 需要看清细节，体积可达数百 MB |
   | HTML + 图片文件夹 | 打成 zip，HTML 只引用相对路径 | 照片数 > 200 或预估体积 > 150 MB 时应用会建议降级到此模式 |

3. 导出前页面会给出**体积预估**，可以先看清文件会有多大再决定。
4. 完成后点结果卡片上的「分享」把文件发给别人，或用文件管理器从 `Android/data/com.plantidentify.full/files/exports/` 取出。

报告排版：每株植物默认只显示「编号 + 封面缩略图 + 中文名 / 俗称 / 拉丁学名」的一行按钮，点击后整屏展开该株的全部信息（照片、百科字段、观察记录）。
禁用 JavaScript 或打印时报告会自动全部展开，不影响阅读与另存为 PDF。

报告为 UTF-8 单文件，内置字体栈适配中文，可直接用浏览器打开。

### 如何备份数据

> 仅完整版。

**备份**

1. 「设置 → 数据管理 → 备份数据」。
2. 生成一个 zip 备份包，内含：
   - `data.json` —— 三张表（植物档案 / 观察记录 / 照片索引）的全部字段，含 AI 原始返回与用户备注
   - `images/` —— 全部照片原图，按库中相对路径存放
   - `manifest.json` —— 版本、条数、生成时间
3. 备份包落在 `files/backups/`，可在同一页的备份列表里看到，并能直接分享出去（发到网盘、传电脑、发给另一台手机）。

**恢复**

1. 把备份包放到手机上任意位置。
2. 「设置 → 数据管理 → 恢复数据」，从列表里选本机备份，或用「从文件选择」挑一个外部 zip。
3. 恢复是**替换**语义：会清空当前三张表并写回备份内容，确认前会显示该备份包含多少株植物、多少次观察、多少张照片。
4. 恢复完成会提示实际写回的条数与照片数；若有照片缺失会单独提示数量。

因为是可迁移格式，备份包可以跨版本、跨设备搬运 —— 基础版与完整版的档案数据完全互通。

## API Key 安全

- **绝不提交真实 API Key**：`local.properties`、`keystore.properties`、`.env`、`*.jks`、`*.keystore` 均已在 `.gitignore` 中排除
- App 运行时用户填写的密钥使用 **Android Keystore AES-256-GCM** 加密后存于本地 DataStore，不上传任何服务器
- **刻意不引入 OkHttp 的 `logging-interceptor`**：它会打印请求头（含 `Authorization: Bearer <apiKey>`）到 Logcat，与「API Key 不出现在日志中」直接冲突
- 所有对外展示或落盘的错误信息都经过脱敏：命中 `sk-…` / `ark-…` / `Bearer …` 形态的片段一律替换为 `***`。崩溃日志走同一套规则
- 应用**没有**任何统计 / 上报 SDK，不做任何埋点
- 图片、档案、备份全部存放在应用私有目录，不对外开放

> 本仓库计划在功能验证完成后转为公开。请注意：**转为公开会暴露全部提交历史**，因此从第一次提交起就按公开标准执行；若历史中曾出现密钥，须先清理历史并吊销密钥，再转公开。

## 目录结构

```
.
├── app/                                       # Android 应用模块（Kotlin + Compose）
│   └── src/
│       ├── main/                              # 主源码集
│       ├── full/res/values/strings.xml        # 完整版的显示名覆盖
│       └── test/                              # 纯 JVM 单元测试
├── tools/                                     # 开发与验收工具（都走 _env.py 解析本机环境，不写死路径）
│   ├── _env.py                                #   本机环境解析（ADB / SDK / 仓库根）
│   ├── mock_ai_server.py                      #   OpenAI 兼容模拟服务端（22 种响应模式）
│   ├── stress_test.py                         #   压测驱动（100 / 1000 / 10000 株，报告落 build/）
│   ├── verify_phase3.py … verify_phase6plus.py#   各阶段验收脚本（UI + 数据库断言）
│   ├── verify_migration_schema.py             #   Room 手写迁移的离线校验
│   ├── smoke_dual_edition.sh                  #   两个版本共存、互不覆盖的冒烟
│   └── verify_release_apk.py                  #   出包自检（支持 --edition base|full）
├── docs/                                      # 面向使用者的文档
│   └── Plant Identify Library 使用教程.pdf      #   图文使用教程
├── Plant Identify.md                          # 需求规格文档（产品与技术需求正本）
├── Plant Identify - 开发计划与可行性分析.md      # 开发计划与可行性分析报告
├── Plant Identify - 压测报告.md                 # 三档压测结论与清洗 OOM 的根因分析
├── README.md
└── .gitignore
```

## 已知限制

**数据清洗在极端档案量下会失败。** 档案量约一万株（约三万张照片）时，「全库深度检查」
会因内存不足而中断 —— 根因是清洗会一次把全库载入内存，内存占用与照片数**严格成正比**
（约 14 KB / 张）。应用会**明确提示失败**并说明「列表是空的并不代表数据真的干净」，
不会假装数据是干净的。

真实使用量远低于这个阈值：**1000 株时深度检查约 95 秒、内存约 150 MB，完全可用。**

完整的三档实测数据（冷启动 / 造数据 / 搜索 / 统计 / 滚动 / 内存 / 清洗）
见 [`Plant Identify - 压测报告.md`](Plant%20Identify%20-%20压测报告.md)。

其他：

- 识别任务的「建任务时拍照 / 调序 / 部位标注」与「现场调查」两个页面尚未实现
- 置信度是模型对当前视觉证据的自信程度，**不是**经过科学验证的物种鉴定概率

## 分支与协作约定

- `main` 为稳定分支，始终保持可编译
- 每个阶段在独立分支上开发
- 变更通过 Pull Request 提交，**经人工审阅确认后**才合并；**不可自动合并**
- 提交前自检：`./gradlew assembleDebug` 零错误、`./gradlew test` 全绿

PR 是**逐层叠加**的：每个 PR 的 base 指向上一个阶段的分支（不是 `main`），
因此**合并顺序必须从底到顶**。

合并用**快进**而不是 squash —— 叠加链天然满足「`main` 是最后一层的祖先」，
快进只移动指针：**不产生新提交、不改写历史**，所有分支指针原地不动，
任何一层都能 `git checkout <分支>` 回到当时。

```bash
# 1) 开一层新的（base 指向当前最后一层分支）
git checkout chore/stress-test-and-release-1.0.1
git checkout -b feature/<new-slug>
# ... 实现与自检 ...
git add -A && git commit -m "feat(...): ..."
git push -u origin feature/<new-slug>
gh pr create --base chore/stress-test-and-release-1.0.1

# 2) 人工确认后，整条链一次性进 main
git checkout main
git merge --ff-only feature/<new-slug>   # --ff-only 是安全闸：main 若已有新提交会直接失败
git push origin main

# 3) base 是 main 的那个 PR 会被自动标记 Merged；其余仍 OPEN，逐个关闭并注明提交号
gh pr close <编号> --comment "内容已随 #<最后一层> 的快进合并进入 main（<SHA>）；分支保留作回滚点。"
```

- **分支一律保留**作回滚点。回滚 `main`：`git push --force origin <合并前 SHA>:main`
  （**只在 main 上还没有新提交时**有效）
- 合并后核对分支指针没动：合并前后各跑一次
  `git for-each-ref --format='%(refname:short) %(objectname:short)' refs/heads/`
  再 diff，**应当只有 `main` 一行变化**


## 免责声明

AI 识别结果仅供参考。对于专业植物鉴定、科研与生产管理等场景，建议由专业人员进一步确认。应用中的置信度是模型对当前视觉证据的置信程度估计，不是经过科学验证的物种鉴定概率。
