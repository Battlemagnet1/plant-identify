# Plant Identify Library

面向园林专业学生、园林园艺从业者、植物学学习者与植物调查人员的 **AI 多图植物识别 + 植物数字档案** Android 应用。

核心特色是**多图联合识别**：一次上传 1–5 张同一株植物的照片（整株 / 叶片 / 花 / 果实 / 树皮），由视觉模型综合所有视觉证据进行判断，而非对每张图独立识别后投票。

## 当前状态

**Phase 1–7 全部完成。**

| Phase | 内容 | 状态 |
|---|---|---|
| 1 | 工程骨架、Compose、Material 3、Navigation、Room 三张表 | ✅ |
| 2 | CameraX、Photo Picker、多图选择与排序、本地图片保存 | ✅ |
| 3 | VisionProvider、多图联合识别、容错 JSON 解析 | ✅ |
| 4 | TextProvider、植物详细分析、识别结果页、低置信度处理 | ✅ |
| 5 | 植物档案、多次观察、详情、编辑、删除、搜索、筛选、归并 | ✅ |
| 6 | 统计、观察地点、HTML 导出、数据备份与恢复 | ✅ |
| — | 实机反馈的七项修复（定位可用性、大图查看、俗称与病虫害、报告折叠、编辑全开放） | ✅ |
| 7 | 性能优化、异常兜底、单元测试、双版本发布 | ✅ |

## 两个版本：基础版与完整版

同一个代码库产出两个可**同时安装、互不覆盖**的 APK：

| | 基础版 base | 完整版 full |
|---|---|---|
| applicationId | `com.plantidentify` | `com.plantidentify.full` |
| 应用显示名 | Plant Identify Library | Plant Identify Library（完整版） |
| 多图识别 · 植物百科 · 档案 · 搜索筛选 · 多次观察 · 归并 · 备注 | ✅ | ✅ |
| 统计页 | — | ✅ |
| 观察地点（定位权限 + 地点记录与展示） | — | ✅ |
| HTML 导出 · 备份 · 恢复（数据管理页） | — | ✅ |
| 常用名称（俗称）与病虫害防治 | — | ✅ |
| 大图查看与保存到相册 | — | ✅ |
| 编辑页的照片增删 | — | ✅ |

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

1. 打开应用 →「添加植物」→ 拍照或从相册选择 1–5 张同一株植物的照片（建议含整株 + 特写，可选标注部位）。
2. 点「开始识别」。识别需要已配置 AI 服务，见下节。
3. 识别结果页可查看置信度、证据、候选物种，并保存为植物档案。
4. 想再补充这株植物的观察记录，从详情页进入并追加照片即可。

> **运行前必须先配置 AI 服务**，否则识别会提示「还没有配置 AI 服务」。

### 位置功能（仅完整版）

进入「设置 → 数据管理」可以打开「记录观察地点」。开启后会在保存档案时记下拍摄地点，用于按地点筛选。
地点**只存在本机**，不会上传到任何服务器。拒绝定位权限不影响拍照、识别与建档。

## 如何配置 API

应用不内置任何 API Key，也不代理请求 —— 由你直接调用自己选择的服务商。

1. 打开应用 →「设置」。
2. 填写三个字段：**Base URL**、**模型名**、**API Key**（见下三节）。
3. 点「测试连接」。通过后会显示服务可用，保存即可。

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
- 视觉识别与文字分析**共用同一个模型配置**。若你的服务商两条通道需要不同模型，请选择同时支持图片与长文本输出的模型。
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

签名包出好后跑一遍自检（检查证书、签名方案版本、包名、版本号、显示名、是否 debuggable、包内是否误带密钥等）：

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
├── tools/                                     # 验收工具
│   ├── mock_ai_server.py                      #   OpenAI 兼容模拟服务端（22 种响应模式）
│   ├── verify_phase3.py … verify_phase6plus.py#   各阶段验收脚本（UI + 数据库断言）
│   └── verify_release_apk.py                  #   出包自检（支持 --edition base|full）
├── Plant Identify.md                          # 需求规格文档（产品与技术需求正本）
├── Plant Identify - 开发计划与可行性分析.md      # 开发计划与可行性分析报告
├── README.md
└── .gitignore
```

## 分支与协作约定

- `main` 为稳定分支，始终保持可编译
- 每个 Phase 在 `feature/phaseN-<slug>` 分支上开发
- 变更通过 Pull Request 提交，**经人工审阅确认后**以 squash 方式合并并删除分支
- 提交前自检：命令行 `gradlew assembleDebug` 零错误

```bash
# 每个 Phase 的标准流程（PR 逐层叠加，base 指向上一阶段分支）
git checkout feature/phaseN-1-<slug>
git checkout -b feature/phaseN-<slug>
# ... 实现与自检 ...
git add -A && git commit -m "feat(phaseN): ..."
git push -u origin feature/phaseN-<slug>
gh pr create --base feature/phaseN-1-<slug>
# 人工审阅确认后：合并、删除分支，并把下一个 PR 的 base 前移
gh pr merge <编号> --squash --delete-branch
```

## 免责声明

AI 识别结果仅供参考。对于专业植物鉴定、科研与生产管理等场景，建议由专业人员进一步确认。应用中的置信度是模型对当前视觉证据的置信程度估计，不是经过科学验证的物种鉴定概率。
