# Plant Identify Library

面向园林专业学生、园林园艺从业者、植物学学习者与植物调查人员的 **AI 多图植物识别 + 植物数字档案** Android 应用。

核心特色是**多图联合识别**：一次上传 1–5 张同一株植物的照片（整株 / 叶片 / 花 / 果实 / 树皮），由视觉模型综合所有视觉证据进行判断，而非对每张图独立识别后投票。

## 当前状态

**Phase 1–5 已完成**，已可出包试用（见 Releases）。Phase 6–7 待开工。

| Phase | 内容 | 状态 |
|---|---|---|
| 1 | 工程骨架、Compose、Material 3、Navigation、Room 三张表 | ✅ |
| 2 | CameraX、Photo Picker、多图选择与排序、本地图片保存 | ✅ |
| 3 | VisionProvider、多图联合识别、容错 JSON 解析 | ✅ |
| 4 | TextProvider、植物详细分析、识别结果页、低置信度处理 | ✅ |
| 5 | 植物档案、多次观察、详情、编辑、删除、搜索、筛选、归并 | ✅ |
| 6 | 统计、地理位置、HTML 导出、数据备份 | 待开工 |
| 7 | 性能优化、异常处理、测试、正式发布 | 待开工 |

## 目录结构

```
.
├── app/                                       # Android 应用模块（Kotlin + Compose）
├── tools/                                     # 验收工具
│   ├── mock_ai_server.py                      #   OpenAI 兼容模拟服务端（22 种响应模式）
│   ├── verify_phase3.py                       #   Phase 3 验收
│   ├── verify_phase4.py / verify_phase4_db.py #   Phase 4 验收（UI + 数据库）
│   └── verify_phase5.py                       #   Phase 5 验收（UI + 数据库）
├── Plant Identify.md                          # 需求规格文档（产品与技术需求正本）
├── Plant Identify - 开发计划与可行性分析.md      # 开发计划与可行性分析报告
├── README.md
└── .gitignore
```

> 两份 Markdown 文档计划在工程稳定后归入 `docs/`。

## 构建

需要 JDK 17 与 Android SDK。SDK 路径写在 `local.properties`（已被忽略），注意 Windows 下盘符冒号要转义成 `C\:/...`。

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 发布包（需签名配置，见下）
./gradlew lintDebug         # 静态检查
```

### 发布包签名

签名密钥与口令**存在仓库之外**，配置写在仓库根目录的 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

该文件不存在时 `assembleRelease` 会退化为产出未签名包，而不是让构建失败 —— 这样其他人 clone 下来无需任何密钥就能正常构建。

> ⚠️ 签名密钥一旦丢失，就无法再对已发布的应用做覆盖升级。请单独备份 `.jks` 文件与口令。

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

## API Key 安全

本应用需要用户自行配置视觉模型与文字分析模型的 API（支持 OpenAI Compatible 接口）。

- **绝不提交真实 API Key**：`local.properties`、`keystore.properties`、`.env`、`*.jks`、`*.keystore` 均已在 `.gitignore` 中排除
- 开发期密钥通过 `local.properties` 注入（该文件被忽略，仅存在于本机）
- App 运行时用户填写的密钥使用 Android Keystore 加密后存于本地 DataStore，不上传任何服务器
- 文档、示例配置、代码注释中的密钥一律使用占位符

> 本仓库计划在功能验证完成后转为公开。请注意：**转为公开会暴露全部提交历史**，因此从第一次提交起就按公开标准执行；若历史中曾出现密钥，须先清理历史并吊销密钥，再转公开。

## 免责声明

AI 识别结果仅供参考。对于专业植物鉴定、科研与生产管理等场景，建议由专业人员进一步确认。应用中的置信度是模型对当前视觉证据的置信程度估计，不是经过科学验证的物种鉴定概率。
