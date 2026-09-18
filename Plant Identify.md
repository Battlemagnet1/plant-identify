你是一名资深 Android 开发工程师、AI 应用架构师、园林植物信息系统设计师和 UI/UX 设计师。

请从零设计并开发一个名为：

# plant Identify

的 Android 应用。

------

# 一、项目定位

plant Identify 是一个面向：

- 园林专业学生
- 园林/园艺从业者
- 植物学学习者
- 植物调查人员
- 林业/生态相关人员
- 普通植物爱好者

的：

> **AI 多图植物识别 + 植物数字档案 + 植物调查记录应用**

不要把它设计成简单的“拍照识花”。

核心流程：

```text
拍照 / 相册
      ↓
添加 1～5 张植物照片
      ↓
AI 多图联合分析
      ↓
植物识别
      ↓
植物专业信息分析
      ↓
建立植物数字档案
      ↓
搜索 / 查看 / 编辑 / 再次观察
      ↓
统计
      ↓
导出 HTML 植物调查报告
```

项目的核心特色：

**多图联合识别，而不是单图识别。**

------

# 二、核心设计思想

一次植物识别可以上传多张图片。

例如：

```text
照片1：完整植株
照片2：叶片
照片3：花朵
照片4：果实
照片5：茎干/树皮
```

AI 不应该把每张图片当成完全独立的识别任务。

应该将它们视为：

> **同一株/同一种植物的不同观察角度**

然后综合所有图片进行判断。

AI Prompt 必须明确要求：

```text
以下图片均来自同一株或同一种植物。

请综合所有图片中的视觉证据进行植物识别。

重点观察：
- 整体株型
- 叶片
- 叶序
- 花
- 果实
- 茎干
- 树皮
- 植物生长形态

不要简单地分别识别每张图片然后投票。

如果不同图片之间存在冲突，应降低识别可信度，并说明冲突原因。
```

------

# 三、多图识别数量

默认允许：

**1～5 张图片。**

未来架构应允许扩展到更多图片。

添加植物页面：

```text
┌──────────────────────────────┐
│ 返回       添加植物           │
├──────────────────────────────┤
│                              │
│        添加植物照片           │
│                              │
│ ┌──────┐ ┌──────┐ ┌──────┐  │
│ │照片1 │ │照片2 │ │照片3 │  │
│ └──────┘ └──────┘ └──────┘  │
│                              │
│ ┌──────┐ ┌──────┐            │
│ │照片4 │ │  +   │            │
│ └──────┘ └──────┘            │
│                              │
│ 已添加 4/5 张                 │
│                              │
│ 建议拍摄：                    │
│ ✓ 整体植株                    │
│ ✓ 叶片                        │
│ ✓ 花/果实                     │
│ ✓ 茎干/树皮                   │
│                              │
│          [开始识别]            │
└──────────────────────────────┘
```

用户可以：

- 相机拍照
- 从相册选择
- 删除某一张
- 更换某一张
- 调整图片顺序

------

# 四、智能拍摄建议

添加植物页面应该提供简单的拍摄建议。

例如：

```text
提高识别准确率

建议至少提供：

① 整株照片
观察整体株型

② 叶片照片
尽量清晰拍摄叶片

③ 花/果实
如果存在花或果实，请拍摄

④ 茎干/树皮
木本植物建议提供
```

不要强制用户必须提供所有类型。

用户只拍一张照片也必须能够识别。

------

# 五、AI识别结果

AI需要返回结构化 JSON。

例如：

```json
{
  "name": "紫薇",
  "latin_name": "Lagerstroemia indica",
  "family": "千屈菜科",
  "genus": "紫薇属",
  "category": "落叶灌木或小乔木",
  "confidence": 0.91,
  "evidence": [
    "叶片形态",
    "整体株型",
    "花部特征"
  ],
  "missing_information": [],
  "possible_alternatives": [
    {
      "name": "大花紫薇",
      "confidence": 0.06
    }
  ],
  "conflicts": []
}
```

如果信息不足：

```json
{
  "name": "紫薇",
  "confidence": 0.62,
  "missing_information": [
    "花部特征",
    "完整株型"
  ],
  "possible_alternatives": [
    {
      "name": "大花紫薇",
      "confidence": 0.25
    }
  ]
}
```

注意：

AI 返回的 confidence 是：

> **AI 对当前视觉证据的置信程度估计**

不是经过科学实验验证的物种鉴定概率。

UI 中不要声称：

“91%一定正确”。

------

# 六、低置信度处理

如果 AI 判断信息不足，例如 confidence < 0.70：

显示：

```text
⚠ 识别可信度较低

当前照片信息不足。

建议补充：

• 完整植株照片
• 叶片近照
• 花朵
• 果实
• 茎干/树皮

[添加更多照片]
[仍然保存当前结果]
```

用户点击：

**添加更多照片**

之后可以继续添加图片。

系统应该：

```text
原有照片
+
新增照片
      ↓
重新进行多图联合识别
      ↓
新的综合结果
```

而不是创建一个完全无关的新植物。

------

# 七、识别质量

除了 confidence，还显示：

```text
识别质量

★★★★★ 优秀
★★★★☆ 良好
★★★☆☆ 一般
★★☆☆☆ 较低
★☆☆☆☆ 很低
```

这个“识别质量”由应用根据置信度和图片信息完整度生成。

例如：

```text
置信度 >= 0.90
★★★★★

0.80～0.89
★★★★☆

0.70～0.79
★★★☆☆

0.60～0.69
★★☆☆☆

< 0.60
★☆☆☆☆
```

不要将其描述成科学评级。

------

# 八、视觉 AI 与文本 AI 分离

这是项目非常重要的架构设计。

不要要求一个模型完成所有任务。

设计：

```text
AIProvider
├── VisionProvider
└── TextProvider
```

其中：

```text
VisionProvider
```

负责：

- 查看植物图片
- 多图联合分析
- 植物识别
- 拉丁学名
- 科
- 属
- 植物类型
- 置信度
- 候选植物

而：

```text
TextProvider
```

负责：

- 植物简介
- 形态特征
- 生长习性
- 花期
- 果期
- 园林用途
- 养护建议
- 专业说明

------

# 九、AI服务商设计

必须允许用户分别配置：

## 植物视觉识别 AI

例如：

```text
Qwen-VL
GLM Vision
OpenAI Vision
Gemini
其他视觉模型
自定义 OpenAI Compatible Vision API
```

## 植物文字分析 AI

例如：

```text
DeepSeek
Qwen
OpenAI
GLM
其他文本模型
自定义 OpenAI Compatible API
```

不要把服务商名称硬编码成不可扩展的逻辑。

------

# 十、OpenAI Compatible API

这是核心要求。

用户应该可以自己填写：

```text
服务名称
Base URL
API Key
Model
```

例如：

```text
服务商：
自定义

Base URL：
https://example.com/v1

API Key：
****************

模型：
vision-model
```

如果 API 遵循 OpenAI Chat Completions 风格：

```text
POST /chat/completions
```

并支持：

```json
{
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "..."
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,..."
          }
        }
      ]
    }
  ]
}
```

则应该可以接入。

不要假设所有服务的模型名称、Base URL、返回字段完全一致。

------

# 十一、默认 AI 配置

应用首次打开时不应该要求用户必须配置 API。

显示：

```text
还没有配置 AI 服务

请前往：

设置 → AI服务

配置视觉模型和文字分析模型。

[前往设置]
```

已经保存的植物记录即使没有 API 也应该可以正常查看。

------

# 十二、API配置页面

设置：

```text
AI服务
────────────────────

植物识别模型

服务类型
[ OpenAI Compatible ▼ ]

Base URL
[                       ]

API Key
[ ********************* ]

模型
[                       ]

[测试连接]


植物分析模型

服务类型
[ DeepSeek ▼ ]

Base URL
[                       ]

API Key
[ ********************* ]

模型
[                       ]

[测试连接]
```

提供：

```text
☑ 使用同一个 AI 服务
```

打开以后：

```text
视觉识别
+
文字分析
```

使用同一个模型/API。

关闭以后：

```text
Vision Provider
Text Provider
```

分别工作。

------

# 十三、图片处理

用户手机拍摄的照片可能非常大。

因此：

```text
原始照片
     │
     ├──→ 保存原图到本地
     │
     └──→ 生成 AI 上传副本
               ↓
          缩放/压缩
               ↓
          上传 Vision API
```

原图不应被破坏。

建议 AI 上传图片最长边控制在合理范围，例如：

1536～2048 px。

JPEG 质量约 75～85。

具体参数根据实际 API 限制调整。

------

# 十四、植物数字档案

数据库必须区分：

**植物档案**

和：

**观察记录**

推荐：

```text
planttRecord
```

保存植物主体：

```text
id
name
latinName
family
genus
category
confidence
description
morphologicalFeatures
growthHabits
floweringPeriod
fruitingPeriod
landscapeUses
careAdvice
createdAt
updatedAt
note
```

然后：

```text
planttObservation
```

保存每一次观察：

```text
id
planttId
timestamp
latitude
longitude
note
aiResult
```

图片单独保存：

```text
ObservationImage
```

例如：

```text
id
observationId
imagePath
sortOrder
```

关系：

```text
planttRecord
   │
   ├── Observation 1
   │       ├── Image 1
   │       ├── Image 2
   │       └── Image 3
   │
   ├── Observation 2
   │       ├── Image 1
   │       └── Image 2
   │
   └── Observation 3
           └── Image 1
```

这样同一种植物可以被观察很多次。

## 十四点五、重复识别与植物记录归并

必须考虑用户重复拍摄同一种植物的情况。

**重复植物和重复观察必须区分：**

- `planttRecord`：代表一种长期保存的植物档案
- `planttObservation`：代表用户某一次对该植物的观察/识别

例如用户第一次识别桂花时创建：

```text
planttRecord #001 桂花
└── Observation #001
```

之后再次拍摄桂花，不应直接创建新的 planttRecord，而应该提示用户是否将此次识别作为新的 Observation 添加到已有植物档案。

### 重复判断流程

```text
AI完成植物识别
        ↓
获取植物名称、拉丁学名、科、属
        ↓
查询本地 planttRecord
        ↓
匹配已有植物
        ↓
根据匹配结果提示用户
```

### 匹配优先级

优先使用：

1. 拉丁学名
2. 中文名 + 科 + 属
3. 中文名 + 科
4. AI语义匹配

不要仅根据中文名称进行绝对判断。

### 匹配结果

#### 高度匹配

例如：

```text
识别结果：桂花
拉丁学名：Osmanthus fragrans

发现已有植物记录：
桂花
Osmanthus fragrans

是否将本次识别添加为新的观察记录？
```

提供：

- `添加到已有植物`
- `创建新的植物`
- `取消`

#### 可能匹配

如果无法确定：

```text
可能已经存在相同植物：

桂花
Osmanthus fragrans

当前识别结果与已有记录存在一定相似性，请确认。
```

必须由用户决定是否归入已有 planttRecord。

#### 无匹配

如果没有找到合理的已有记录：

> 创建新的植物档案

### 重要原则

AI不得未经用户确认直接合并两个已有 planttRecord。

系统可以自动推荐“可能是已有植物”，但最终归并操作由用户确认。

如果用户选择“添加到已有植物”，则：

```text
已有 planttRecord
        ↓
创建新的 planttObservation
        ↓
保存本次识别结果和全部照片
```

而不是覆盖原来的观察记录。

原有观察记录必须保留。

### 同一观察的重新识别

如果用户只是对当前 Observation 添加更多照片并重新识别：

```text
Observation #001
├── 原有照片
├── 新增照片
└── 重新进行多图联合识别
```

此时更新当前 Observation 的识别结果，而不是创建新的 Observation。

### 数据结构目标

最终数据库应支持：

```text
planttRecord
├── Observation 1
│   ├── Image 1
│   ├── Image 2
│   └── Image 3
│
├── Observation 2
│   ├── Image 1
│   └── Image 2
│
└── Observation 3
    └── Image 1
```

这样同一种植物可以拥有长期、多次、多地点的观察历史。

------

# 十五、首页

顶部：

```text
plant Identify
v1.0.0
```

右侧：

```text
⚙ 设置
📷 拍照
```

中间：

```text
🔍 搜索植物
```

然后：

```text
植物记录

已记录植物：127
不同植物：83
```

下面使用卡片。

卡片：

```text
┌──────────────────────────┐
│                          │
│       植物照片            │
│                          │
├──────────────────────────┤
│ 紫薇                     │
│ Lagerstroemia indica     │
│ 千屈菜科 · 紫薇属         │
│                          │
│ AI置信度：91%             │
│ 观察次数：3               │
│ 2026-09-18               │
└──────────────────────────┘
```

点击进入详情。

------

# 十六、植物详情

页面：

```text
植物照片

紫薇
Lagerstroemia indica

千屈菜科 · 紫薇属

AI识别置信度：91%

识别质量：★★★★☆

────────────────

基本信息

中文名称
紫薇

拉丁学名
Lagerstroemia indica

科
千屈菜科

属
紫薇属

植物类型
落叶灌木/小乔木

────────────────

AI植物分析

植物简介

形态特征

叶片特征

花部特征

果实特征

生长习性

花期

果期

园林用途

养护建议

────────────────

相似植物

大花紫薇
……

────────────────

观察记录

首次观察：
2026-09-01

观察次数：
3

[查看所有观察]
```

允许用户手动编辑。

------

# 十七、观察记录

每一次观察都可以保存：

```text
观察时间
观察地点
照片
AI识别结果
备注
```

例如：

```text
紫薇

观察 #003

2026-09-18 14:32

📍 校园南门

照片：
[整体] [叶片] [花朵] [树皮]

AI识别：
紫薇

置信度：
91%

备注：
校园道路旁绿化植物
```

------

# 十八、位置功能

位置是可选功能。

首次使用时：

```text
是否记录植物观察地点？

[允许]
[暂不允许]
```

如果用户拒绝：

APP仍然必须正常工作。

保存：

```text
latitude
longitude
```

如果条件允许，可以进行反向地理编码。

显示：

```text
📍 某校园
```

而不是强制显示经纬度。

------

# 十九、搜索

首页搜索框：

```text
🔍 搜索植物
```

支持：

- 中文名称
- 拉丁学名
- 科
- 属
- 植物类型
- 备注

例如：

```text
紫薇
```

显示：

```text
紫薇
Lagerstroemia indica

3次观察
```

支持进一步筛选：

```text
科
属
日期
地点
```

------

# 二十、统计

提供植物统计：

```text
植物统计

植物记录：
127

不同植物：
83

观察次数：
156

科：
42

属：
67
```

统计必须准确区分：

```text
植物记录
```

和：

```text
不同植物
```

例如：

同一种紫薇观察3次：

```text
植物记录：3
不同植物：1
```

------

# 二十一、HTML导出

设置：

```text
数据管理

[导出 HTML]
```

导出全部植物记录。

文件：

```text
plantIdentify_Report_2026-09-18.html
```

HTML结构：

```text
plant Identify
植物调查报告

生成时间
2026-09-18

植物记录
127

不同植物
83

────────────────

01 紫薇

[照片]

中文名称：
紫薇

拉丁学名：
Lagerstroemia indica

科：
千屈菜科

属：
紫薇属

植物类型：
落叶灌木/小乔木

AI识别置信度：
91%

植物简介：
……

形态特征：
……

生长习性：
……

园林用途：
……

观察记录：
……

────────────────

02 桂花

……
```

优先生成：

**单文件 HTML**

图片使用 Base64/Data URI 嵌入。

这样用户只需要一个 HTML 文件，就可以在其他设备打开。

如果图片数量过多，可以考虑提供：

```text
单文件 HTML
```

和：

```text
HTML + 图片文件夹
```

两种模式。

------

# 二十二、数据备份

在设置增加：

```text
数据管理

[导出 HTML]

[备份数据]

[恢复数据]
```

备份应该能够保存：

- 植物档案
- 观察记录
- AI分析结果
- 本地图片
- 用户备注

优先设计成可迁移的数据格式。

------

# 二十三、离线能力

已经保存的植物档案必须能够：

- 离线查看
- 离线搜索
- 离线编辑
- 离线删除
- 离线导出

只有：

```text
AI识别
AI分析
反向地理编码
```

等功能需要网络。

------

# 二十四、错误处理

必须处理：

```text
没有网络
API Key错误
API Base URL错误
API余额不足
API请求超时
模型不存在
模型不支持图片
服务器返回错误
服务器返回非JSON
AI返回格式错误
图片过大
图片格式错误
数据库错误
存储失败
权限被拒绝
位置权限被拒绝
```

所有错误都应该给用户可理解的提示。

不要直接显示：

```text
NullPointerException
HTTP 400
JSON Parse Error
```

这种开发者错误信息。

可以在“详细错误”中提供技术信息。

------

# 二十五、API Key安全

API Key：

- 不允许写死在源码
- 不允许提交到 Git
- 日志中不得打印
- UI中默认隐藏
- 使用 Android Keystore 等安全方案保护
- 不应该上传到开发者自己的服务器

如果使用 DataStore 保存配置，需要考虑敏感信息的加密。

------

# 二十六、UI设计

整体风格：

**现代、简洁、专业、植物主题。**

推荐：

- Material 3
- Jetpack Compose
- 圆角卡片
- 大面积留白
- 清晰的信息层级
- 简洁图标
- 植物主题色作为强调色
- 不要过度装饰

不要：

- 大量渐变
- 大量动画
- 复杂侧边栏
- 满屏按钮
- 传统老式 Android UI

首页应该让用户打开应用之后立即知道：

> “我要拍植物 / 看我的植物记录。”

------

# 二十七、推荐页面结构

```text
MainActivity
     │
     ↓
HomeScreen
     │
     ├── AddplanttScreen
     │      ├── Camera
     │      └── Gallery
     │
     ├── RecognitionScreen
     │
     ├── planttDetailScreen
     │
     ├── ObservationScreen
     │
     ├── SearchScreen
     │
     └── SettingsScreen
             ├── AISettings
             ├── RecognitionSettings
             ├── DataManagement
             └── About
```

------

# 二十八、项目技术架构

推荐：

```text
Kotlin
Jetpack Compose
Material 3
Room
CameraX
Android Photo Picker
Retrofit
OkHttp
Coroutines
ViewModel
Navigation Compose
DataStore
Android Keystore
```

架构：

```text
UI
 ↓
ViewModel
 ↓
UseCase
 ↓
Repository
 ↓
 ├── Room
 ├── File Storage
 └── AI Provider
```

------

# 二十九、AI Provider架构

设计成：

```text
AIProvider
│
├── VisionProvider
│     ├── OpenAICompatibleVisionProvider
│     ├── QwenVisionProvider
│     ├── GLMVisionProvider
│     └── ...
│
└── TextProvider
      ├── OpenAICompatibleTextProvider
      ├── DeepSeekProvider
      ├── QwenTextProvider
      └── ...
```

但是如果多个服务都遵循 OpenAI API，可以优先统一：

```text
OpenAICompatibleProvider
```

避免大量重复代码。

------

# 三十、识别流程

完整流程：

```text
用户点击“添加植物”
        ↓
选择拍照 / 相册
        ↓
添加1～5张图片
        ↓
用户确认
        ↓
保存原图
        ↓
生成AI压缩图片
        ↓
Vision Provider
        ↓
多图联合识别
        ↓
解析JSON
        ↓
验证结果
        ↓
显示识别结果
        ↓
用户确认
        ↓
Text Provider
        ↓
生成植物详细分析
        ↓
保存 planttRecord
        ↓
保存 planttObservation
        ↓
保存图片
        ↓
返回植物详情
```

如果 Vision Provider 失败：

```text
停止识别
显示错误
允许重试
```

如果 Text Provider 失败：

```text
仍然保存基础植物识别结果

并显示：

“详细植物分析暂时生成失败”

[重新生成分析]
```

也就是说：

**文字分析失败不能导致植物识别结果丢失。**

------

# 三十一、非常重要：AI结果不能完全信任

APP需要明确：

```text
AI识别结果仅供参考。

对于专业植物鉴定、科研、生产管理等场景，
建议由专业人员进一步确认。
```

不要声称 AI：

```text
100%准确
保证鉴定正确
专业鉴定结果
```

------

# 三十二、未来扩展能力

项目架构需要为未来功能留下空间：

```text
植物地图
植物分类统计
校园植物调查
植物调查路线
植物群落调查
PDF报告
Excel/CSV导出
本地视觉模型
Pl@ntNet
植物数据库
多人共享植物库
```

但第一版不要实现所有未来功能。

优先保证 MVP 稳定。

------

# 三十三、第一阶段开发顺序

不要一次性生成整个项目。

按照以下 Phase 开发：

## Phase 1

完成：

- 项目初始化
- Compose
- Material 3
- 首页
- Navigation
- Room
- planttRecord
- planttObservation
- ObservationImage

确保可以编译运行。

## Phase 2

完成：

- CameraX
- Photo Picker
- 多图片选择
- 图片预览
- 图片删除
- 图片排序
- 本地图片保存

确保可以完整完成：

```text
拍照 → 添加多张图片 → 本地保存
```

## Phase 3

完成：

- VisionProvider
- OpenAI Compatible API
- 多图请求
- JSON解析
- 错误处理
- API配置
- API测试

确保：

```text
图片 → Vision AI → 结构化植物识别结果
```

## Phase 4

完成：

- TextProvider
- DeepSeek/OpenAI Compatible
- 植物详细分析
- 识别结果页面
- 低置信度提示
- 添加更多图片重新识别

## Phase 5

完成：

- 植物档案
- 多次观察
- 植物详情
- 编辑
- 删除
- 搜索
- 筛选

## Phase 6

完成：

- 统计
- 地理位置
- HTML导出
- 单文件HTML
- 图片嵌入
- 数据备份

## Phase 7

完成：

- UI优化
- 性能优化
- 权限处理
- API异常处理
- 数据库异常处理
- 测试
- Release构建
- README

------

# 三十四、开发要求

每完成一个 Phase：

1. 检查项目是否可以编译
2. 检查已有功能是否被破坏
3. 修复编译错误
4. 修复明显运行时错误
5. 再进入下一阶段

不要为了追求代码数量而生成大量没有经过验证的代码。

如果某个 API、SDK 或依赖版本不确定，优先查阅官方最新文档，不要凭记忆编写已经可能过时的代码。

------

# 三十五、最终交付

最终需要得到一个：

**真正可以安装运行的 Android APK 项目。**

需要提供：

```text
完整 Android Studio 项目
完整源码
Gradle 配置
README
数据库
AI Provider
Compose UI
CameraX
相册
多图识别
植物档案
搜索
统计
HTML导出
数据备份
错误处理
```

README说明：

```text
如何导入项目

如何运行

如何配置 API

如何配置 Base URL

如何配置模型

如何使用 OpenAI Compatible API

如何构建 APK

如何导出 HTML

如何备份数据

API Key 安全注意事项
```

------

# 三十六、开发前必须先做的事情

**现在不要立即生成完整代码。**

第一步只需要输出：

### 1. 完整技术架构

### 2. 项目目录结构

### 3. Room 数据库 ER 结构

### 4. 页面结构

### 5. Navigation结构

### 6. AI Provider接口设计

### 7. 多图识别数据流程

### 8. API请求/响应数据结构

### 9. HTML导出方案

### 10. 权限方案

### 11. 图片存储方案

### 12. Phase 1 的具体开发计划

先让我确认架构。

**在没有得到确认之前，不要生成完整项目代码。**

确认之后，从 Phase 1 开始逐步实现。