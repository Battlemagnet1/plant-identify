package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plantidentify.data.local.entity.LandscapeFolderDataEntity
import kotlinx.coroutines.flow.Flow

/**
 * 景观文件夹扩展数据的读写。
 *
 * ## 为什么 Phase 1 的注释说「刻意不建 DAO」，现在又建了
 *
 * Phase 1 的原话是「本阶段只把结构建好，读写它的代码属于 Phase 3」——
 * 那时确实没有任何调用方，建一个空 DAO 就是死代码。
 *
 * **Phase 2 出现了第一个真实调用方：备份。** 景观数据必须随备份走，
 * 否则用户换机恢复后，景观文件夹会悄悄退化成普通文件夹
 * （植物都在、位置和项目信息却没了）。于是这时候建 DAO 是必要的，不是提前设计。
 *
 * 方法刻意只有两个 —— 正好是备份与恢复需要的。
 * 景观照片、植物配置统计、AI 分析、报告仍然属于 Phase 3。
 */
@Dao
interface LandscapeFolderDataDao {

    @Query("SELECT * FROM landscape_folder_data")
    suspend fun getAll(): List<LandscapeFolderDataEntity>

    @Query("SELECT * FROM landscape_folder_data WHERE folderId = :folderId")
    suspend fun getByFolder(folderId: Long): LandscapeFolderDataEntity?

    @Query("SELECT * FROM landscape_folder_data WHERE folderId = :folderId")
    fun observeByFolder(folderId: Long): Flow<LandscapeFolderDataEntity?>

    /**
     * 写入单条（插入或覆盖）。
     *
     * 主键就是 `folderId`，所以「有没有记录」等价于「有没有这个文件夹的档案」——
     * 不必先查再决定走 insert 还是 update。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LandscapeFolderDataEntity)

    /**
     * 批量写入（恢复备份用）。
     *
     * 用 REPLACE 而不是 IGNORE：恢复前的库已经被清空，
     * 此时出现同主键只可能是包内自身重复，让后者覆盖前者即可。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LandscapeFolderDataEntity>)
}
