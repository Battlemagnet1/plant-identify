# plant Identify

面向园林专业学生、园林园艺从业者、植物学学习者与植物调查人员的 **AI 多图植物识别 + 植物数字档案** Android 应用。

核心特色是**多图联合识别**：一次上传 1–5 张同一株植物的照片（整株 / 叶片 / 花 / 果实 / 树皮），由视觉模型综合所有视觉证据进行判断，而非对每张图独立识别后投票。

## 当前状态

**规划阶段**。尚未开始编码，Android 工程骨架将在 Phase 1 生成。

已完成：
- 需求规格文档
- 开发计划与可行性分析报告

已完成的技术前提确认：构建环境、工具链版本矩阵、视觉模型多图能力核查、核心风险识别与验证实验设计。

## 目录结构

```
.
├── Plant Identify.md                          # 需求规格文档（产品与技术需求正本）
├── Plant Identify - 开发计划与可行性分析.md      # 开发计划与可行性分析报告
├── README.md
└── .gitignore
```

> Android 工程建立后（Phase 1），两份 Markdown 文档计划归入 `docs/`，工程放于仓库根目录。

## 开发计划概览

按 7 个 Phase 递进交付，每个 Phase 完成后才进入下一阶段：

| Phase | 内容 |
|---|---|
| 1 | 项目初始化、Compose、Material 3、Navigation、Room 三张表 |
| 2 | CameraX、Photo Picker、多图选择与排序、本地图片保存 |
| 3 | VisionProvider、OpenAI Compatible API、多图请求与 JSON 解析 |
| 4 | TextProvider、植物详细分析、识别结果页、低置信度处理 |
| 5 | 植物档案、多次观察、详情、编辑、搜索、筛选 |
| 6 | 统计、地理位置、HTML 导出、数据备份 |
| 7 | UI 与性能优化、权限与异常处理、测试、Release 构建 |

## 分支与协作约定

- `main` 为稳定分支，始终保持可编译
- 每个 Phase 在 `feature/phaseN-<slug>` 分支上开发
- 变更通过 Pull Request 提交，**经人工审阅确认后**以 squash 方式合并并删除分支
- 提交前自检：命令行 `gradlew assembleDebug` 零错误

```bash
# 每个 Phase 的标准流程
git checkout main && git pull --ff-only
git checkout -b feature/phaseN-<slug>
# ... 实现与自检 ...
git add -A && git commit -m "feat(phaseN): ..."
git push -u origin feature/phaseN-<slug>
gh pr create --base main
# 人工审阅确认后
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
