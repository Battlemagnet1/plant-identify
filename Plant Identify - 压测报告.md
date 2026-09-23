# Plant Identify — 压测报告（Phase 4）

**日期**：2026-09-23 　**分支**：`chore/stress-test-and-release-1.0.1`
**被测**：`com.plantidentify.full` debug 构建（模拟器，Android 14 / API 34，1920×1080）
**驱动**：`tools/stress_test.py`（脚本写报告到 `build/stress-report-<N>.txt`，本目录被 gitignore）

对应分析报告 `Plant Identify - 开发计划与可行性分析.md` 的 **§12 风险 #5
「10000 条时清洗的全量载入可能 OOM」** —— 这一轮把它从「可能」压成了「确定」。

---

## 一、三档结果

| 指标 | 100 株 | 1000 株 | 10000 株 | 趋势 |
|---|---|---|---|---|
| 冷启动 `am_start TotalTime` | 1163 ms | 1550 ms | 1077 ms | 常数 |
| 首页可交互 | 2.16 s | 2.48 s | 2.39 s | 常数 |
| 造数据（app 内计时） | 0.7 s | 3.3 s | 13.7 s | 亚线性 |
| 内存 · 造完后 | 112.1 MB | 117.7 MB | 120.9 MB | 几乎不变 |
| 内存 · 滚动后 | 107.8 MB | 112.9 MB | 117.2 MB | 几乎不变 |
| 搜索 · 打开（全量渲染） | 8.26 s | 8.24 s | 8.05 s | 常数 |
| 搜索 · 输入过滤 | 2.22 s | 2.22 s | 2.14 s | 常数 |
| 统计详情页 | 2.00 s | 1.99 s | 1.98 s | 常数 |
| 滚动 · p90 帧耗时 | 15 ms | 10 ms | 9 ms | 常数 |
| 滚动 · janky 比例 | 17.1% | 6.2% | 5.6% | 常数 |
| **清洗 · 全库深度检查** | **3.1 s** | **95.1 s** | **OOM，未完成** | ⚠️ **超线性** |

**除清洗外，全部指标是常数或亚线性。** 搜索打开耗时被脚本 1 秒粒度的轮询盖住
（三档都在 8.1–8.3 s），所以它衡量的是「UI 驱动开销」而不是渲染时间；口径三档一致，
横向可比。列表是惰性的，所以 10000 株下首页滚动甚至比 100 株更稳（janky 5.6%）。

**唯一的问题集中在数据清洗这一条路上**，其余部分在 10000 株下完全健康。

---

## 二、清洗：三个叠加的缺陷

### 1. 耗时超线性

10× 数据 → 30.7× 耗时。反解指数：`log(30.7) / log(10) ≈ 1.49`。
按此外推，10000 株约需 **48 分钟**。

`data/cleaning/CleaningDataLoader.kt` 的类注释里已经写着这件事：

> 档案规模到几千条时这里会变成瓶颈，届时再按需加载；现在几百条的规模下，
> 一次全读比十次精确查询更快也更容易对。

这一次是把它压出来了。

### 2. 10000 株时 OOM

| 证据 | 数值 |
|---|---|
| logcat | `Throwing OutOfMemoryError "… 191MB/192MB, <1% of heap free after GC"`（18:24:08，全局仅此一次） |
| `image_fingerprint` | 冻结在 **13333 / 29998**（44%），此后 3 分钟纹丝不动 |
| `cleaning_state` | **0 行** —— 扫描没写回任何游标 |
| 进程 | 仍存活、无 `FATAL EXCEPTION`、无 ANR；36 秒后 GC 把堆从 192 MB 收回 18 MB |

`ImageFingerprinter.scan()` 是全仓库唯一写指纹表的地方（另一个调用点只有
`PlantIdentifyApplication` 的构造注入），所以「扫描跑到 44% 死掉」在逻辑上是闭合的。

### 3. 失败被显示成「很干净」（**与数据量无关的正确性缺陷**）

扫描死于 OOM 之后，界面显示的是：

```
100 分 · 很干净
共 10000 株档案，没有发现需要处理的问题
数据很干净 —— 没有发现缺失字段、异常坐标、重复照片或疑似重复的档案。
```

而种子数据**故意**造了缺失字段（每 7 条缺学名、每 11 条缺科）—— 不可能一条都没有。

两个原因叠在一起：

- **`ui/screens/cleaning/DataCleaningScreen.kt` 的失败提示只走 Snackbar**，
  而且 `LaunchedEffect` 显示完立刻 `consumeMessage()`。4 秒后页面恢复默认态，
  默认态恰好就是「很干净」。另外 `OutOfMemoryError.message` 是 `null`，
  所以那条一闪而过的提示实际写的是「检查失败：**null**」。
- **`CandidateSetBuilder` 的降噪丢弃没有置 `partial`**。10000 株时 12 个物种
  每个约 833 条，全部倒排表都超过 `MAX_POSTING_LIST = 300` 被无条件丢弃，
  于是候选集为 0 —— 但界面拿不到这个事实。「没有候选」被显示成「没有问题」。

---

## 三、这一轮做的修复

按「先修与数据量无关的正确性缺陷、分批载入另立一阶段」的范围执行：

| 文件 | 改动 |
|---|---|
| `domain/cleaning/CandidateSetBuilder.kt` | 新增 `CandidateResult.skippedRecords` =「只出现在被丢弃倒排表里、因而没参与任何比对」的档案数 |
| `data/cleaning/CleaningOrchestrator.kt` | `CleaningRunSummary` += `skippedRecords`，由编排层透传 |
| `ui/screens/cleaning/CleaningViewModel.kt` | 新增**持久**的 `lastScanError`（只有扫描成功才清）；`refresh()` 也接住异常（否则 `loading` 永远为 true、页面一直转圈）；`scanFailureText()` 按异常类型给可行动的话 |
| `ui/screens/cleaning/DataCleaningScreen.kt` | 新增 `ScanErrorCard`（errorContainer + 「知道了」）并置于列表**最前**；失败时健康卡的标签从「很干净」换成「上次检查未完成」；空列表改说「这次没有结论」 |
| `app/src/test/.../CandidateSetBuilderTest.kt` | 新增 2 个用例 + 强化 2 个已有用例（原来那条「全库同名超出阈值 - 已知取舍」断言 `partial` 为 false，正是这个盲点让「0 候选」看起来像「查过了」） |

### 未修的部分（明确留给下一阶段）

**`CleaningDataLoader.load()` 仍然一次读全库**（10000 档案 + 20000 观察 + 30000 照片行），
以及 `ImageFingerprinter.scan()` 的内存峰值仍未定位到具体对象。
所以 **10000 株下点「全库深度检查」仍然会失败** —— 不同的是，现在它会
**如实告诉用户失败了**，而不是假装干净。

真实用户的档案量远在这个阈值之下（1000 株的深度检查 95 秒、内存 152 MB，完全可用），
所以这一条不阻塞 v1.0.1 的发布。

---

## 四、怎么复现

```bash
# 设备已连接（ADB_DEVICE 可指定），full 的 debug 包已安装
python tools/stress_test.py run 100        # 约 3 分钟
python tools/stress_test.py run 1000       # 约 6 分钟
python tools/stress_test.py run 10000 --clean-timeout 900
```

⚠️ 这一脚本会**清空 `com.plantidentify.full` 的全部数据**（基础版不受影响）。

`tools/mock_ai_server.py` 不参与压测：造数据与清洗都是纯本地路径，不联网。

### 驱动脚本自身的三个坑（已修，记在这里免得再踩）

1. **查设备库必须用 `adb exec-out`，不能用 `adb shell`。** `shell` 会把参数交给
   设备端 `sh` 再解析一遍，SQL 里的 `(` `)` 是元字符 → 报错、stdout 空串被当成 0，
   **全程不报错**。症状是「造数据明明成功、脚本判定失败」。
2. **点 UI 前要确认节点的中心点在安全区（0.05H–0.95H）。** 只看「顶端在屏内」
   会漏掉 `[102,1051,249,1080]` 这类节点 —— 中心 y=1065 落在系统导航条上，
   点击被系统吃掉。
3. **Compose 里的文字大多不可点**（可点的是外层 Card/Button），要沿父链找到
   最近的可点祖先再点它的中心。搜索框更不能按 `H*0.09` 猜坐标 —— 那是标题栏，
   输入框实际在 `y≈179–293`。
