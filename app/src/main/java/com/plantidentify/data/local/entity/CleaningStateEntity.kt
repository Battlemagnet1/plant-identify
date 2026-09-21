package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 清洗扫描的游标（**单行表**，[id] 恒为 [SINGLETON_ID]）。
 *
 * ## 为什么要游标
 *
 * 全库扫描的代价随档案数线性增长（还要算照片哈希）。用户档案攒到几百株后，
 * 每次打开清洗中心都全扫一遍是不可接受的。
 *
 * 增量策略：只检查 `updatedAt > lastCheckedAt` 的植物 + 与它们有关的候选。
 * 用户想全扫时点「全库深度检查」忽略游标即可。
 *
 * ## 为什么要 cleaningVersion
 *
 * 游标有个天然缺陷：**改算法不会让游标失效**。我调低了相似度阈值、
 * 加了新规则，但所有档案的 `updatedAt` 都没变 → 增量扫描认为「无事可做」，
 * 新规则永远跑不起来，而且没有任何报错。
 *
 * 所以每次改算法/阈值/规则集，**人为把 [cleaningVersion] + 1**；
 * 扫描时发现存的版本不等于当前版本就自动全扫一次。这是一个
 * 「必须靠人记得」的约定 —— 它比自动检测便宜得多（自动检测要么哈希整个
 * 规则源码，要么把阈值也塞进表里，都不划算），所以写在注释里当纪律。
 */
@Entity(tableName = "cleaning_state")
data class CleaningStateEntity(

    @PrimaryKey
    val id: Long = SINGLETON_ID,

    /** 上次扫描的时间戳；0 = 从未扫过（首次必然全扫） */
    val lastCheckedAt: Long = 0L,

    /** 上次扫描时使用的算法版本；与当前版本不等则强制全扫 */
    val cleaningVersion: Int = 0,
) {
    companion object {
        /** 单行表固定主键 */
        const val SINGLETON_ID = 1L
    }
}
