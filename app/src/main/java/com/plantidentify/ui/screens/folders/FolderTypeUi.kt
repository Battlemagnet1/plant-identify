package com.plantidentify.ui.screens.folders

import com.plantidentify.data.local.entity.FolderType

/**
 * 文件夹类型在界面上的显示名。
 *
 * 单独抽出来是因为它要被**四处**共用：主页筛选行、卡片上的类型徽标、
 * 详情页信息卡、编辑页的类型选择。分开写会让「景观」在某处变成
 * 「景观文件夹」、再在某处变成「LANDSCAPE」—— 同一个概念三种叫法，
 * 用户会怀疑它们是不是不同的东西。
 *
 * 不放到 domain 层：domain 只放纯逻辑与算法，显示文案属于界面。
 */
internal fun FolderType.label(): String = when (this) {
    FolderType.LANDSCAPE -> "景观"
    FolderType.COLLABORATION -> "协作"
    FolderType.CUSTOM -> "自定义"
}

/**
 * 新建文件夹时给的一句用途说明。
 *
 * 三种类型光看名字（尤其是「协作」）并不容易选对，
 * 而选错的后果是「该有的功能入口不见了」—— 用户不会往类型上想。
 */
internal fun FolderType.hint(): String = when (this) {
    FolderType.LANDSCAPE -> "校园、公园、小区等场地的植物配置调查"
    FolderType.COLLABORATION -> "先接收别人发来的植物数据，确认后再合并进自己的库"
    FolderType.CUSTOM -> "纯粹的组织归类，比如「广东常见植物」「待确认植物」"
}
