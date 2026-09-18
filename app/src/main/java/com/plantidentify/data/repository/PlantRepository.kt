package com.plantidentify.data.repository

import com.plantidentify.data.local.dao.ObservationImageDao
import com.plantidentify.data.local.dao.PlantObservationDao
import com.plantidentify.data.local.dao.PlantRecordDao
import com.plantidentify.domain.model.PlantStatistics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 植物档案仓库 —— UI 层访问数据的唯一入口。
 *
 * 架构分层：UI → ViewModel → UseCase → Repository → (Room / File / AI)
 *
 * Phase 1 只实现统计相关读取（用于验证 Room 全链路打通）。
 * 后续 Phase 会在此补齐：
 *   - Phase 2：图片文件存储（原图落盘、副本压缩）
 *   - Phase 3：AI 视觉识别结果落库
 *   - Phase 4：文字分析结果落库与重试
 *   - Phase 5：档案 CRUD、观察增删、重复植物归并
 *   - Phase 6：统计扩展、备份恢复
 */
class PlantRepository(
    private val plantRecordDao: PlantRecordDao,
    private val observationDao: PlantObservationDao,
    private val imageDao: ObservationImageDao,
) {

    /**
     * 观察统计。
     *
     * 注意：五项统计的分母不同（见 [PlantStatistics] 注释），
     * 这里用 combine 合并为一条 Flow，UI 侧保证三者始终同步更新。
     */
    fun observeStatistics(): Flow<PlantStatistics> = combine(
        plantRecordDao.observeDistinctPlantCount(),
        observationDao.observeObservationCount(),
        imageDao.observeImageCount(),
        plantRecordDao.observeFamilyCount(),
        plantRecordDao.observeGenusCount(),
    ) { distinctPlants, observationCount, imageCount, familyCount, genusCount ->
        PlantStatistics(
            distinctPlants = distinctPlants,
            observationCount = observationCount,
            imageCount = imageCount,
            familyCount = familyCount,
            genusCount = genusCount,
        )
    }
}
