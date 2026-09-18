package com.plantidentify.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity

/** 一次观察 + 它的全部照片 */
data class ObservationWithImages(
    @Embedded
    val observation: PlantObservationEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "observationId",
    )
    val images: List<ObservationImageEntity>,
)

/** 一种植物 + 它的全部观察记录（不含照片，用于列表页与统计） */
data class PlantWithObservations(
    @Embedded
    val plant: PlantRecordEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "plantId",
    )
    val observations: List<PlantObservationEntity>,
)

/**
 * 一种植物 + 全部观察 + 每次观察的全部照片。
 * 用于植物详情页与 HTML 导出。
 */
data class PlantWithObservationsAndImages(
    @Embedded
    val plant: PlantRecordEntity,

    @Relation(
        entity = PlantObservationEntity::class,
        parentColumn = "id",
        entityColumn = "plantId",
    )
    val observations: List<ObservationWithImages>,
)
