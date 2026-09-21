package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plantidentify.data.local.entity.ImageFingerprintEntity

/**
 * 照片哈希缓存的读写。
 *
 * `imagePath` 是主键，所以 [upsert] 用 REPLACE —— 与清洗问题表相反，
 * 这里**没有用户决策要保护**，缓存值本身就是「最新的算得对」。
 */
@Dao
interface ImageFingerprintDao {

    @Query("SELECT * FROM image_fingerprint WHERE imagePath = :path LIMIT 1")
    suspend fun getByPath(path: String): ImageFingerprintEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImageFingerprintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ImageFingerprintEntity>)

    /**
     * 有重复内容的哈希值（出现次数 > 1）。
     *
     * 只返回 sha256，路径用 [getByHash] 二次查询 ——
     * 分组聚合里塞不下列表，硬塞只能拼字符串再拆，不如两次查询清楚。
     */
    @Query(
        "SELECT sha256 FROM image_fingerprint " +
            "GROUP BY sha256 HAVING COUNT(*) > 1",
    )
    suspend fun findDuplicateHashes(): List<String>

    @Query("SELECT * FROM image_fingerprint WHERE sha256 = :sha256 ORDER BY imagePath ASC")
    suspend fun getByHash(sha256: String): List<ImageFingerprintEntity>

    @Query("SELECT imagePath FROM image_fingerprint")
    suspend fun allPaths(): List<String>

    @Query("SELECT COUNT(*) FROM image_fingerprint")
    suspend fun count(): Int

    @Query("DELETE FROM image_fingerprint WHERE imagePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM image_fingerprint")
    suspend fun clearAll()
}
