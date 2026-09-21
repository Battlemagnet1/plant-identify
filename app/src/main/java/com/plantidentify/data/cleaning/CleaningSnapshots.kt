package com.plantidentify.data.cleaning

import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.domain.cleaning.ObservationSnapshot
import com.plantidentify.domain.cleaning.RecordSnapshot

/**
 * 实体 → 领域快照的映射。
 *
 * 单独成文件而不是塞进编排器：合并预览页、AI 清洗顾问、未来的统计口径
 * 都要构造快照。放在一处，字段映射只有一份 ——
 * 「加了新字段却忘了加进快照」的表现是清洗算法对那个字段永远视而不见，
 * 而且不会有任何报错。
 */
fun PlantRecordEntity.toSnapshot(
    observationCount: Int,
    imageCount: Int,
): RecordSnapshot = RecordSnapshot(
    id = id,
    name = name,
    latinName = latinName,
    commonNames = commonNames,
    family = family,
    genus = genus,
    category = category,
    confidence = confidence,
    description = description,
    morphologicalFeatures = morphologicalFeatures,
    growthHabits = growthHabits,
    floweringPeriod = floweringPeriod,
    fruitingPeriod = fruitingPeriod,
    landscapeUses = landscapeUses,
    careAdvice = careAdvice,
    pestControl = pestControl,
    note = note,
    updatedAt = updatedAt,
    observationCount = observationCount,
    imageCount = imageCount,
)

fun PlantObservationEntity.toSnapshot(imagePaths: List<String>): ObservationSnapshot =
    ObservationSnapshot(
        id = id,
        plantId = plantId,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        aiResultJson = aiResultJson,
        imagePaths = imagePaths,
    )
