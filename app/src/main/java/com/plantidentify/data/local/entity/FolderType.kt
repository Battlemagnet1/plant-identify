package com.plantidentify.data.local.entity

/**
 * 文件夹类型（需求文档第三节）。
 *
 * 三种类型不是「三套数据」，而是同一个通用容器上的三种**用途**：
 *   - [LANDSCAPE]     景观文件夹：承载校园 / 公园 / 小区等场地的植物配置调查
 *   - [COLLABORATION] 协作文件夹：别人发来的植物数据先落在这里，确认后再合并进自己的库
 *   - [CUSTOM]        自定义文件夹：纯粹的组织归类，没有额外业务逻辑
 *
 * ## 为什么用枚举而不是字符串
 *
 * Room 用内置转换器把它存成 TEXT（存 `name()`）—— SQL 里仍是可读的
 * `'LANDSCAPE'`，而 Kotlin 侧拿不到非法值。
 *
 * ## 只能追加，不能改名
 *
 * 存的是 `name()`：一旦有行落库，重命名枚举常量会让老库的 `valueOf`
 * 直接抛异常。要加新类型请往后面加。
 */
enum class FolderType {
    LANDSCAPE,
    COLLABORATION,
    CUSTOM,
}
