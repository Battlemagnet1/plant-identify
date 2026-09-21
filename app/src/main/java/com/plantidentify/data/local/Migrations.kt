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
     * v4 → v5：清洗功能的三张表（Phase 3）。
     *
     * ## 为什么这三张表要单独一次迁移
     *
     * 方案原本把它们和 `deletedAt`、任务表一起塞进 `MIGRATION_2_3`。
     * 实际落地时拆成了三次，理由是**它们的风险面完全不同**：
     * 任务表与软删都会改变既有查询的语义（必须连同 DAO 改动一起发），
     * 而这三张是**全新的表、没有任何既有代码读它们** ——
     * 建表本身不可能影响任何现有功能，可以和上层逐步交付。
     *
     * 拆开还有一个直接好处：如果清洗的迁移真出了问题，
     * 回滚范围只有「清洗功能不可用」，用户的档案与回收站毫发无伤。
     *
     * ## 三张表各自的定位
     *
     * | 表 | 性质 | 丢了会怎样 |
     * |---|---|---|
     * | `cleaning_issue` | 用户决策的载体 | 「忽略」失效，每个问题每次扫描重问一遍 |
     * | `image_fingerprint` | 纯缓存 | 只是下次检查慢一点（要重算 SHA-256） |
     * | `cleaning_state` | 游标 | 下次自动全扫一遍 |
     *
     * 所以备份里**三张都不带**（见 §3.5）—— 前两张是派生数据，
     * 第三张是「本机扫到哪儿了」，跨机迁移没有意义，
     * 而恢复备份后旧值只会导致扫少或重扫，不会导致数据错误。
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ① 清洗问题。fingerprint 唯一索引 = 「忽略」能生效的全部机制
            // 各列的顺序与 PlantRecordEntity 的字段顺序一致 ——
            // 下面这几段 CREATE TABLE 是**照抄 Room 生成的 createSql** 的
            // （见 app/schemas/.../5.json）。SQLite 其实不在乎列的先后，
            // 但 Room 的迁移校验会拿手写 SQL 与生成 SQL 对齐比较，
            // 写法一致才能让「核对过」这件事由脚本机械完成，
            // 而不是靠人肉逐字读一遍。
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cleaning_issue` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`fingerprint` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`severity` TEXT NOT NULL, " +
                    "`recordIds` TEXT NOT NULL, " +
                    "`discriminator` TEXT, " +
                    "`similarity` REAL, " +
                    "`reason` TEXT NOT NULL, " +
                    "`aiUsed` INTEGER NOT NULL, " +
                    "`aiReason` TEXT, " +
                    "`status` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_cleaning_issue_fingerprint` " +
                    "ON `cleaning_issue` (`fingerprint`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cleaning_issue_status` " +
                    "ON `cleaning_issue` (`status`)",
            )

            // ② 照片哈希缓存。主键是相对路径 → 新增照片天然是一条新行
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `image_fingerprint` (" +
                    "`imagePath` TEXT NOT NULL, " +
                    "`sha256` TEXT NOT NULL, " +
                    "`sizeBytes` INTEGER NOT NULL, " +
                    "`modifiedAt` INTEGER NOT NULL, " +
                    "`computedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`imagePath`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_image_fingerprint_sha256` " +
                    "ON `image_fingerprint` (`sha256`)",
            )

            // ③ 扫描游标（单行，id 恒为 1）
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cleaning_state` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`lastCheckedAt` INTEGER NOT NULL, " +
                    "`cleaningVersion` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
        }
    }

    /**
     * 全部迁移，按版本升序。
     *
     * 顺序不能乱 —— Room 会从当前版本开始，逐个往上找能匹配起点的迁移。
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
    )
}
