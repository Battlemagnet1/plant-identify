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
     * v2 → v3：新增「识别任务」两张表。
     *
     * 纯增量：不动任何既有表、不加列。老用户的档案在升级后原样可用，
     * 只是多出两个空表 —— 这是迁移里风险最低的一种。
     *
     * ## 为什么这两张表要一起上
     *
     * `recognition_task_image` 有指向 `recognition_task` 的外键。
     * 分两次迁移（先建主表、下个版本再建子表）在中间那个版本里会存在
     * 「任务没有图片」的非法中间态，而任务的唯一用途就是装图片 ——
     * 没有图片的任务是脏数据，不该能被表示出来。
     *
     * ## 列顺序与 NOT NULL 必须和 Room 生成的建表语句逐字一致
     *
     * 下面的 SQL 是手写的，Room 不会替我们校验。它比对的是
     * **「列名集合 + 约束 + 索引」**，任何一处不符都会在**用户设备上**
     * 抛 `Migration didn't properly handle`（构建期发现不了）。
     * 写完必须拿构建产物 `schemas/3.json` 逐条核对，并按 §11.5 真机验证。
     *
     * 非空列（`status` / `createdAt` / `retryCount` / `priority` / `origin` /
     * `taskId` / `imagePath` / `role` / `sortOrder`）在 Kotlin 侧都是非空类型，
     * 所以 SQL 里**不能带默认值**、也不加 `NOT NULL DEFAULT`——
     * Room 生成的建表语句对这类列就是裸的 `TEXT NOT NULL`。
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recognition_task` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`startedAt` INTEGER, " +
                    "`completedAt` INTEGER, " +
                    "`retryCount` INTEGER NOT NULL, " +
                    "`errorMessage` TEXT, " +
                    "`resultObservationId` INTEGER, " +
                    "`priority` INTEGER NOT NULL, " +
                    "`origin` TEXT NOT NULL, " +
                    "`code` TEXT, " +
                    "`pendingMergePlantId` INTEGER, " +
                    "`pendingMergeLevel` TEXT, " +
                    "`pendingMergeReason` TEXT, " +
                    "`note` TEXT)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recognition_task_status` ON `recognition_task` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recognition_task_createdAt` ON `recognition_task` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recognition_task_priority` ON `recognition_task` (`priority`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recognition_task_image` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`taskId` INTEGER NOT NULL, " +
                    "`imagePath` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`sortOrder` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`taskId`) REFERENCES `recognition_task`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recognition_task_image_taskId_sortOrder` " +
                    "ON `recognition_task_image` (`taskId`, `sortOrder`)",
            )
        }
    }

    /**
     * v3 → v4：档案软删除（回收站）。
     *
     * 只加一列 + 一个索引 —— 可空列的老行自动 NULL = 未删除，
     * 正是「既有档案都没被删过」该有的语义。
     *
     * ## 为什么这一列的发布风险比它看起来高
     *
     * 加列本身零风险，但它会**改变既有查询的语义**：
     * 加完之后所有列表/搜索/统计/归并候选都必须补 `deletedAt IS NULL`，
     * 漏一处就会出现「删了还在这里」，或者更糟的
     * **「已删档案变成归并候选」**（用户把 A 删了，识别到同种植物时
     * 又被提示「要不要并入 A」）。
     *
     * 所以这一版**必须连同 PlantRecordDao 的整张过滤清单一起发布**，
     * 不能拆成「先加列、下次再改查询」。
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE plant_record ADD COLUMN deletedAt INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_plant_record_deletedAt` " +
                    "ON `plant_record` (`deletedAt`)",
            )
        }
    }

    /**
     * 全部迁移，按版本升序。
     *
     * 顺序不能乱 —— Room 会从当前版本开始，逐个往上找能匹配起点的迁移。
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
