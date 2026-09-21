package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plantidentify.data.local.entity.CleaningStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 扫描游标的读写（单行表）。
 *
 * 用 REPLACE 而不是 UPDATE：即使行不存在（首次安装、被 `clearAll` 清过），
 * 一次 `put` 也能把状态写好，调用方不必先判断「有没有行」。
 */
@Dao
interface CleaningStateDao {

    @Query("SELECT * FROM cleaning_state WHERE id = ${CleaningStateEntity.SINGLETON_ID} LIMIT 1")
    suspend fun get(): CleaningStateEntity?

    @Query("SELECT * FROM cleaning_state WHERE id = ${CleaningStateEntity.SINGLETON_ID} LIMIT 1")
    fun observe(): Flow<CleaningStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: CleaningStateEntity)

    @Query("DELETE FROM cleaning_state")
    suspend fun clearAll()
}
