# AGENTS.md — 给 AI agent / 新开发者的接手说明

> **本文件随仓库走。** 原开发机上还有更详细的资料，但它们位于 `.workbuddy/`
> （已被 `.gitignore` 排除，不随仓库分发）：
>
> - `.workbuddy/skills/plant-identify-development/SKILL.md` —— 完整开发手册
> - `.workbuddy/memory/CONVENTIONS.md` —— **踩坑与设计理由的详情页，改代码前必读**
> - `.workbuddy/memory/HANDOVER.md` —— 交接快照（当前状态、待办、已知缺陷）
> - `.workbuddy/memory/MEMORY.md` + `2026-*.md` —— 索引与按日期的工作日志
>
> 如果拿不到这些文件，本文件 + `README.md` + `Plant Identify.md` 足以开工，
> 但**第 3 节的硬规矩请务必逐条遵守** —— 每一条都是踩过坑才写下的。

## 1 · 这是什么

**Plant Identify Library** —— Android 单机 App。
拍照 / 选图 → AI **多图识别**植物 → 归档成「植物档案」，再支持观察记录、统计、
文件夹、导入导出、景观分析。

- **技术栈**：Kotlin + Jetpack Compose + Room + OkHttp + WorkManager + CameraX
- **无自建后端**，数据全在本机；AI 走 OpenAI 兼容接口，密钥由用户在设置页自填
- **两个分发版本**：`base`（`com.plantidentify`）/ `full`（`com.plantidentify.full`）——
  同一份代码、同一个库结构，差别**只在界面入口显不显示**
- 仓库公开 + MIT

## 2 · 上手

```bash
export JAVA_HOME="/path/to/jdk-17"       # 必须 JDK 17；不在 PATH 时每次都要设
# Android SDK 路径写在 local.properties 的 sdk.dir（参考 local.properties.example）

./gradlew :app:testFullDebugUnitTest                       # 单测（约 272 用例）
./gradlew :app:assembleBaseDebug :app:assembleFullDebug     # 两个 debug 包
./gradlew :app:lintFullRelease                             # ⚠️ 是 FullRelease，不是 lintDebug
./gradlew :app:assembleBaseRelease :app:assembleFullRelease  # 出正式签名包
```

**⚠️ `./gradlew test` 整片失败 ≠ 你写坏了代码**：每个用例都报
`Gradle Test Executor … non-zero exit value 1`（连老测试类也挂）是**进程环境变量过大**
导致 fork 测试 JVM 失败。绕过：

```bash
export PATH=$(printf '%s' "$PATH" | tr ':' '\n' | awk '!seen[$0]++' | paste -sd:)
./gradlew --no-daemon test
```

设备（模拟器，网络 ADB）：

```bash
adb devices
adb -s <serial> shell am start -n com.plantidentify.full/com.plantidentify.MainActivity
```

**别拼 `<PKG>/.MainActivity`** —— 完整版 applicationId 带 `.full` 而类名不带，
简写会展开成**不存在的类**、`am start` **静默失败**。

验收脚本在 `tools/`：`verify_phase3/4/4_db/5/6/6plus`、`smoke_dual_edition.sh`、
`stress_test.py`、`mock_ai_server.py`、`verify_migration_schema.py`（**改库必跑**）、
`verify_release_apk.py`（**发版前必跑**）。新脚本取设备一律用 `tools/_env.py`，**绝不写死 IP**。

## 3 · 必须遵守的规矩

**数据库**

1. 改实体字段要**同时改三处**：实体类 / `Migrations.kt` 的 SQL / DB `version`。
2. **严禁 `fallbackToDestructiveMigration`** —— 它会静默清库；列名写错只在**用户设备上**抛异常。
   改完必须在**有旧数据的设备上覆盖安装**验一次，并跑 `tools/verify_migration_schema.py`。

**架构**

3. `AppEdition.isFull` **只写在界面入口**，绝不散进业务逻辑 ——
   散进去两个包的数据就不再互通，备份包也就不能互相迁移了。
4. 领域模型**必须带自己的主键**（曾因缺主键导致「点哪张卡都进同一条问题」且不报错）。
5. 列表一律用 `observeXxx()` 的 Flow，不要「一次性读 + 手动 refresh」。

**数据语义**

6. **导入 ≠ 恢复**：恢复是整体替换（先清空）；**导入是增量合并（绝不清空）**，
   主键**全部重新分配**、图片**逐张判重**。
7. **软删的植物不会自动清关联** → 文件夹成员查询必须带 `p.deletedAt IS NULL`。
8. 界面/导出的计数**只算「在用」档案**，回收站里的不算。
9. **Pl@ntNet 与视觉 AI 的 confidence 语义不同**，**禁止相加 / 平均 / 比大小**；
   后台批量识别**固定走视觉 AI**。

**界面**

10. **提示不能只走 Snackbar** —— 失败（尤其清洗扫描的 OOM）**必须持久留在页面上**；
    否则 4 秒后页面恢复成「100 分 · 数据很干净」，那是比崩溃更糟的谎。
11. **归并只提示、绝不自动合并。**
12. Compose 的 `Text` **不渲染 markdown**；日期格式化必须用
    `ui/components/DateFormat.kt` 的 `rememberDateFormatter()`，否则 lint 判 error。
13. 图片存 `filesDir`（**严禁 `cacheDir`**），库里只存相对路径。

## 4 · 分支与发版

`功能分支 ─PR→ develop（CI + 单测）─绿→ main`，main 走 **`--ff-only` 快进合并**，
分支一律保留作回滚点，**不自动合并**（人工确认后才合）。

**发版前先核对上一版的 `versionCode`**（`versionCode` 不涨的后果是
「安装成功但跑的还是旧版本」，非常安静）：

```bash
git show <上个 tag>:app/build.gradle.kts | grep -E "versionCode|versionName"
gh release list --limit 5
```

两条分发线**各自递增**。Release 说明**必须写 APK SHA-256 与证书 SHA-256**。
资产命名 `plant-identify-{base,full}-vX.Y.Z.apk`。
⚠️ `gh release create "file#name"` 的别名在 gh 2.97 上**不生效** —— 先 `cp` 成目标名字再上传。
⚠️ **在受限网络上 `gh release upload` 会失败**（上行 ~3.4 KB/s、7–20 秒必断，
`HTTP 100` 之后被切断）。变通办法是走 SSH 通道：把 APK 提交到一个
**孤立分支**（本仓库是 `release-assets`）再 push，用户从
`github.com/<owner>/<repo>/raw/release-assets/release-apk/<文件名>` 下载。
提交时**务必只 `git add` 那两个 apk**，别用 `git add -A`（孤立分支会把整个源码树带进去）。

**CI 绿 ≠ 可以发版**：CI 只跑 `test assembleDebug`（两个 flavor 的单测 + 两个 debug 包），
**不含 lint、不含 release 变体、不验数据库迁移**。发版前的迁移正确性、lint、R8 仍要本机手工跑。
另：别顺手点 `workflow_dispatch` 的「正式发行版」—— 它发的是 debug 包，会盖掉 `v1.0.x` 的 Latest。

## 5 · 当前状态（2026-10-11 晚）

- `main = develop = 30ea58b`，**v1.0.2.1011 已发布**（base `code 3` / full `code 4`）
- 单测 **293 用例全绿**（v1.0.2 时期的 272 + 新增 21）
- **下一步：修 10000 株时清洗深度检查的 OOM** ——
  `CleaningDataLoader.load()` 用 `getAll()` 一次读全库（单株带 8 个百科长文本 ≈14 KB），
  堆上限 192 MB 直接崩。修法是**分页 / 轻量投影读取**
  （可参照导入侧已经做过的 `MatchSnapshotRow`）。
  另有一项系统性问题：全项目 **0 处 `rememberSaveable`**，进程被杀后页面状态全丢。

### Pl@ntNet 有三条「改回去就坏」的约定

v1.0.2.1011 刚修完，都是**不报错**或**报错指向错误方向**的坑：

1. **端点必须是 `https://my-api.plantnet.org/v2`** ——
   `my.plantnet.org` 是网站不是 API 主机、`/v1` 已下线，用错**恒返回 404**，
   而且**与 API Key 对不对毫无关系**（用户会反复重填一个正确的 Key）。
2. **multipart 必须「先 `organs` 后 `images`」**（官方示例顺序）——
   配对靠**位置**，顺序错了不报错，只会把部位配到别的图片上。
3. **请求必须带 `lang=zh`**，且要从 `commonNames` 里挑**含汉字**的那个 ——
   Pl@ntNet 默认回英文，且即使指定中文也会混入拼音、顺序不保证
   （`["叶子花","Ye zi hua","Yè zi huā"]`）。本项目按中文名做搜索与重复检测，
   两个识别通道语言不一致会导致**同一株植物变成两个不同的名字**。

另：**配置校验一律按当前通道**（`AiConfig.isUsableFor(channel)`）。
保存门控与识别前置校验都曾经只看「视觉配置」，害得选 Pl@ntNet 的用户
连保存按钮都点不动。

## 6 · 文档地图

| 文件 | 是什么 |
|---|---|
| `Plant Identify.md` | 总设计规格（约 1800 行） |
| `v1.0.2.txt` | 当前版本的任务书 |
| `Plan Identify 新功能开发任务.md` | 新功能任务清单 |
| `Plant Identify - 开发计划与可行性分析.md` | 最详尽的技术方案与风险标注 |
| `Plant Identify - 压测报告.md` | 100 / 1000 / 10000 株三档性能数据 |
| `README.md` | 面向使用者与贡献者的门面 |
| `docs/` | 图文使用教程（PDF） |
