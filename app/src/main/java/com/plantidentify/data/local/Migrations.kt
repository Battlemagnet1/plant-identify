package com.plantidentify.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.plantidentify.data.local.entity.PlantRecordEntity

/**
 * 数据库迁移。
 *
 * ## 硬约束
 *
 * 1. **禁止 `fallbackToDestructiveMigration()`**
 *    用户档案是长期资产。破坏性迁移在升级时会静默清空全部记录 ——
 *    而用户根本不会预期「更新一次应用，植物就没了」。
 *
 * 2. **每提升一次 version 就必须配一个显式 Migration**
 *    Room 在打开数据库时会比对 schema，版本对不上又没有迁移，
 *    会直接抛 `IllegalStateException`。构建期不会报错，
 *    只有用户装上之后才崩 —— 这是最坏的一种失败时机。
 *
 * 3. **新增可空列一律 `ALTER TABLE ... ADD COLUMN`**
 *    可空且无默认值的列不需要重建表，老行的值自动为 NULL，
 *    正是「旧数据没有这个字段」该有的语义。
 *
 * ## 手工维护的列名清单
 *
 * 下面的 SQL 是手写的，Room 不会替我们校验列名是否与实体一致 ——
 * 而 **schema 校验是按「列名集合」比对的**，写错一个字母就会在
 * 用户设备上抛 `Migration didn't properly handle`。
 * 因此每加一列，都要同时改三处：实体、这里、以及构建后重新生成的 schema JSON。
 *
 * 列名的权威来源是 PlantRecordEntity，两边必须一致。
 */
object Migrations {

    /**
     * v1 → v2：植物档案新增「常用名称 / 俗称」与「病虫害防治建议」。
     *
     * 两列都来自文字分析（百科）通道，都可空：
     *   - 老档案没有这两列的内容，升级后为 NULL，界面按「未填写」处理
     *   - 备份包里的旧字段集依然合法（解码逐个 `opt*` 取值，缺失即 null）
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE plant_record ADD COLUMN commonNames TEXT")
            db.execSQL("ALTER TABLE plant_record ADD COLUMN pestControl TEXT")
        }
    }

    /**
     * 全部迁移，按版本升序。
     *
     * 顺序不能乱 —— Room 会从当前版本开始，逐个往上找能匹配起点的迁移。
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
