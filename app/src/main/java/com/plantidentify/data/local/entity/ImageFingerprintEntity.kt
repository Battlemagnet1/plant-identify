package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 照片内容指纹（SHA-256）缓存。
 *
 * ## 为什么需要缓存
 *
 * 判「两张照片是不是同一张」只能算内容哈希，而算哈希要把文件读一遍。
 * 一次全库检查若有 500 张照片、每张 200 KB，就是 100 MB 的磁盘读 ——
 * 而且**每次点检查都要重来一遍**，因为照片本身几乎不变。
 *
 * 缓存命中判据是 `(sizeBytes, modifiedAt)`：文件内容没变时这两个值也不变。
 * 比「按 mtime 判」更稳（复制文件能保留 mtime 却改了内容？那是同内容复制），
 * 比「按路径判」更稳（路径会因为备份恢复而整体变化，内容却没变）。
 *
 * **[imagePath] 作主键** → 天然增量：新增照片是一条新行，
 * 删掉的照片由 `deleteByPath` / 孤儿清理带走。
 *
 * 注意存的是**相对路径**（与 `observation_image.imagePath` 同口径），
 * 这样备份恢复到另一台设备后缓存仍然对得上。
 */
@Entity(
    tableName = "image_fingerprint",
    indices = [Index(value = ["sha256"])],
)
data class ImageFingerprintEntity(

    @PrimaryKey
    val imagePath: String,

    /** SHA-256 十六进制小写 */
    val sha256: String,

    val sizeBytes: Long,

    val modifiedAt: Long,

    /** 算出这个值的时间，仅用于排查「缓存是不是很久没更新了」 */
    val computedAt: Long,
) {
    /**
     * 能否直接复用这条缓存来代表 [size] / [modified] 的那个文件。
     *
     * 抽成方法而不是在调用处写 `if`，是因为这个判据有两个使用者
     * （指纹器的增量路径、孤儿清理时判断该不该留），
     * 分开写迟早会有一处忘记比对 size。
     */
    fun matches(size: Long, modified: Long): Boolean =
        sizeBytes == size && modifiedAt == modified
}
