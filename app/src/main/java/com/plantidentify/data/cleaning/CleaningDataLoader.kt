package com.plantidentify.data.cleaning

import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.domain.cleaning.ObservationSnapshot
import com.plantidentify.domain.cleaning.RecordSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 一次清洗所需的**全部只读数据**。
 *
 * 把「要读什么」和「读到之后怎么算」分开：读的部分只有这一处，
 * 于是四个消费者（扫描、合并预览、AI 顾问、清洗中心页面）看到的
 * 字段口径必然一致。
 *
 * @param records 未删除的档案快照
 * @param knownPlantIds **全部**档案 id，含回收站里的 —— 孤儿观察的判定基准
 * @param observations 全库观察（含已删档案名下的）
 * @param orphanImageCount 找不到所属观察的照片行数
 */
data class CleaningDataset(
    val records: List<RecordSnapshot>,
    val knownPlantIds: Set<Long>,
    val observations: List<ObservationSnapshot>,
    val orphanImageCount: Int,
) {
    fun recordById(id: Long): RecordSnapshot? = records.firstOrNull { it.id == id }

    /** 某株名下的观察 */
    fun observationsOf(plantId: Long): List<ObservationSnapshot> =
        observations.filter { it.plantId == plantId }

    val byId: Map<Long, RecordSnapshot> by lazy { records.associateBy { it.id } }

    companion object {
        val EMPTY = CleaningDataset(emptyList(), emptySet(), emptyList(), 0)
    }
}

/**
 * 把三张表读成清洗要用的快照。
 *
 * ## 为什么一次性全读，而不是按需查
 *
 * 清洗的每一步都要反复用到「全部档案」（候选集要与全库比对、
 * 重复照片要知道每张图的归属、孤儿判断要知道所有父 id）。
 * 按需查会变成 N 次往返，而且**每次都要记得把「含回收站」的口径带上** ——
 * 那正是最容易漏、漏了只表现为「多报一堆假问题」的地方。
 *
 * 档案规模到几千条时这里会变成瓶颈，届时再按需加载；
 * 现在几百条的规模下，一次全读比十次精确查询更快也更容易对。
 */
class CleaningDataLoader(private val database: PlantIdentifyDatabase) {

    private val recordDao = database.plantRecordDao()
    private val observationDao = database.plantObservationDao()
    private val imageDao = database.observationImageDao()

    /**
     * 未删除档案数的**可观察**读取。
     *
     * 清洗中心的健康度分母要用它，而档案数会被别处改变（添加、删除、合并）——
     * 一次性读会让界面停在旧的分母上，表现为「删了一株，分数没变」。
     */
    fun observeRecordCount(): Flow<Int> = recordDao.observeDistinctPlantCount()

    suspend fun load(): CleaningDataset {
        // 注意用 getAll()（**不过滤 deletedAt**）再自己分组 ——
        // 回收站里的档案也必须是合法的父记录，否则删一株植物
        // 会让它名下的观察全变成「无主孤儿」
        val plants = recordDao.getAll()
        val knownPlantIds = plants.mapTo(HashSet()) { it.id }

        val observations = observationDao.getAll()
        val images = imageDao.getAll()

        val imagesByObservation = images.groupBy { it.observationId }
        val observationsByPlant = observations.groupBy { it.plantId }

        val records = plants
            .filter { it.deletedAt == null }
            .map { plant ->
                val own = observationsByPlant[plant.id].orEmpty()
                plant.toSnapshot(
                    observationCount = own.size,
                    imageCount = own.sumOf { imagesByObservation[it.id]?.size ?: 0 },
                )
            }

        val observationSnapshots = observations.map { obs ->
            obs.toSnapshot(imagesByObservation[obs.id].orEmpty().map { it.imagePath })
        }

        val observationIds = observations.mapTo(HashSet()) { it.id }
        val orphanImageCount = images.count { it.observationId !in observationIds }

        return CleaningDataset(
            records = records,
            knownPlantIds = knownPlantIds,
            observations = observationSnapshots,
            orphanImageCount = orphanImageCount,
        )
    }

    /**
     * 多株的封面图。
     *
     * 逐株查（而不是一条 `IN` 查询）是因为**每株要取它的第一张**，
     * 而 SQL 的窗口函数在这里不值得用。清洗页一次最多显示十几株，
     * 十几次主键查询的代价可以忽略。
     */
    suspend fun coverPaths(plantIds: Collection<Long>): Map<Long, String> =
        plantIds.distinct().mapNotNull { id ->
            imageDao.coverPathForPlant(id)?.let { id to it }
        }.toMap()

    /** 单株的观察数 / 照片数（详情页要显示「3 次观察 · 5 张照片」） */
    suspend fun statsOf(plantId: Long): Pair<Int, Int> {
        val observations = observationDao.getByPlant(plantId)
        val images = observations.sumOf { imageDao.countByObservation(it.id) }
        return observations.size to images
    }
}
