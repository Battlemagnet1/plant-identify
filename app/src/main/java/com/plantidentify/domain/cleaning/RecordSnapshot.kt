package com.plantidentify.domain.cleaning

/**
 * 档案的**只读快照**。
 *
 * ## 为什么不让算法直接吃 `PlantRecordEntity`
 *
 * `domain/cleaning/` 的契约是「纯算法、零 Android 依赖、全可 JVM 单测」。
 * 直接吃 Room 实体的话，单测会连带把 `androidx.room` 的注解类拉进来 ——
 * 而这些东西在 JVM 单测里是打不通的桩。
 *
 * 快照还有一个更实际的好处：**一次扫描只查一遍数据库**。
 * 清洗要反复两两比较（候选集、六级判定、字段择优都有），
 * 如果每次都回查实体，一个 300 株的库会打出上万次查询。
 */
data class RecordSnapshot(
    val id: Long,
    val name: String,

    val latinName: String? = null,
    val commonNames: String? = null,
    val family: String? = null,
    val genus: String? = null,
    val category: String? = null,
    val confidence: Double = 0.0,

    val description: String? = null,
    val morphologicalFeatures: String? = null,
    val growthHabits: String? = null,
    val floweringPeriod: String? = null,
    val fruitingPeriod: String? = null,
    val landscapeUses: String? = null,
    val careAdvice: String? = null,
    val pestControl: String? = null,
    val note: String? = null,

    val updatedAt: Long = 0L,

    /** 观察次数 —— 合并时用它决定「默认保留哪一株」（多的活着） */
    val observationCount: Int = 0,

    /** 照片数 —— 合并预览要显示「合并后有 N 张照片」 */
    val imageCount: Int = 0,
) {
    fun isEmptyField(field: MergeField): Boolean = when (field) {
        MergeField.NAME -> name.isBlank()
        MergeField.LATIN_NAME -> latinName.isNullOrBlank()
        MergeField.FAMILY -> family.isNullOrBlank()
        MergeField.GENUS -> genus.isNullOrBlank()
        MergeField.CATEGORY -> category.isNullOrBlank()
        MergeField.COMMON_NAMES -> commonNames.isNullOrBlank()
        MergeField.DESCRIPTION -> description.isNullOrBlank()
        MergeField.MORPHOLOGICAL_FEATURES -> morphologicalFeatures.isNullOrBlank()
        MergeField.GROWTH_HABITS -> growthHabits.isNullOrBlank()
        MergeField.FLOWERING_PERIOD -> floweringPeriod.isNullOrBlank()
        MergeField.FRUITING_PERIOD -> fruitingPeriod.isNullOrBlank()
        MergeField.LANDSCAPE_USES -> landscapeUses.isNullOrBlank()
        MergeField.CARE_ADVICE -> careAdvice.isNullOrBlank()
        MergeField.PEST_CONTROL -> pestControl.isNullOrBlank()
        MergeField.NOTE -> note.isNullOrBlank()
    }
}

/**
 * 观察记录的只读快照。
 *
 * `imagePaths` 直接摊平进来（而不是让规则去查图片表）——
 * 规则关心的只有「这次观察有几张照片、路径是什么」，
 * 照片自身的 id / role / sortOrder 对清洗判定没有意义。
 */
data class ObservationSnapshot(
    val id: Long,
    val plantId: Long,
    val timestamp: Long,

    val latitude: Double? = null,
    val longitude: Double? = null,

    /** AI 原始结果（用于「JSON 坏掉」这一类规则的抽检） */
    val aiResultJson: String? = null,

    /** 相对路径，与 `observation_image.imagePath` 同口径 */
    val imagePaths: List<String> = emptyList(),
)

/**
 * 照片文件的可达性。
 *
 * 判定「文件坏了」需要 `BitmapFactory`，那是 Android API —— 所以
 * 由编排层（`data/cleaning`）扫描后把结果传进来，算法只消费结论。
 *
 * **缺失与损坏分开**：缺失常常是「用户手动删过文件」或「同步不完整」，
 * 损坏则更可能意味着存储出问题，两者的严重程度与处置都不同。
 */
data class ImageHealth(
    /** 数据库里有记录，但磁盘上找不到文件 */
    val missing: Set<String> = emptySet(),

    /** 文件在，但解不出图（0 字节、截断、非图片内容） */
    val broken: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = missing.isEmpty() && broken.isEmpty()

    companion object {
        val NONE = ImageHealth()
    }
}
