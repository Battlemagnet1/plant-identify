package com.plantidentify.data.cleaning

import android.graphics.BitmapFactory
import com.plantidentify.data.local.dao.ImageFingerprintDao
import com.plantidentify.data.local.entity.ImageFingerprintEntity
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 照片内容指纹（SHA-256）+ 缓存。
 *
 * ## 为什么要缓存
 *
 * 判「两张照片是不是同一张」只能算内容哈希，而算哈希要把文件整个读一遍。
 * 一次全库检查若有 500 张照片、每张 200 KB，就是 100 MB 的磁盘读 ——
 * 而**每次点「检查」都要重来一遍**，因为照片几乎不变。
 *
 * 缓存命中判据是 `(sizeBytes, modifiedAt)`，不是路径：
 * 备份恢复到另一台设备后路径会整体重写、内容却没变，按路径判会让整库重算。
 *
 * ## 顺带产出「文件健不健康」
 *
 * 既然已经把每张照片摸了一遍，顺手返回：文件在不在、能不能解出图。
 * 分成两次扫描（先查存在性、再查可解码性）等于把同一个目录读两遍。
 */
class ImageFingerprinter(
    private val imageStore: ImageStore,
    private val dao: ImageFingerprintDao,
) {

    private companion object {
        /** 一次扫描最多清理多少条失效缓存，见 [pruneStale] */
        const val MAX_PRUNE_PER_SCAN = 500
    }

    /**
     * 一次扫描的产出。
     *
     * @param shaByPath 路径 → 内容哈希（**只含存在且可读的文件**）
     * @param missing 数据库里有记录、磁盘上没有
     * @param broken 文件在，但解不出图
     */
    data class ScanReport(
        val shaByPath: Map<String, String>,
        val missing: Set<String>,
        val broken: Set<String>,
    ) {
        /**
         * 内容相同的照片分组（sha256 → 路径，至少 2 张）。
         *
         * 只留下**不同路径**的组合：同一路径不可能在库里出现两次
         * （`image_fingerprint` 的主键就是路径），这里再判一次是为了
         * 让调用方不必去想这件事。
         */
        fun duplicateGroups(): Map<String, List<String>> =
            shaByPath.entries
                .groupBy({ it.value }, { it.key })
                .filterValues { it.size > 1 }
    }

    suspend fun scan(paths: Collection<String>): ScanReport = withContext(Dispatchers.IO) {
        val sha = HashMap<String, String>(paths.size)
        val missing = HashSet<String>()
        val broken = HashSet<String>()

        for (path in paths) {
            val file = imageStore.resolve(path)
            if (!file.isFile) {
                missing += path
                continue
            }
            if (!isDecodable(file)) {
                broken += path
                // 坏文件不进指纹表：它随时可能被修复或替换，
                // 留着哈希只会让「重复照片」多出一批假阳性
                continue
            }
            sha[path] = fingerprintOf(path, file)
        }

        pruneStale(sha.keys)
        ScanReport(sha, missing, broken)
    }

    /**
     * 清掉已经不属于任何照片的缓存行（照片被删、被彻底删除）。
     *
     * 不用一条 `DELETE ... WHERE imagePath NOT IN (:keep)`：SQLite 的
     * 绑定变量上限是 999（老设备上更低），而一个库完全可能有一千多张照片，
     * 那时这条语句会**在用户设备上**抛 SQLiteException。
     * 反过来查一次「表里都有谁」再逐个删，代价只有几十次单行删除。
     *
     * 单次上限 500：这是一次「顺手清理」，不该让用户点检查时等它。
     * 剩下的下次扫描继续清 —— 删不干净不影响正确性（只是缓存大一点）。
     */
    private suspend fun pruneStale(keep: Collection<String>) {
        val keepSet = keep.toHashSet()
        val stale = dao.allPaths().filterNot { it in keepSet }.take(MAX_PRUNE_PER_SCAN)
        stale.forEach { dao.deleteByPath(it) }
    }

    /** 取（或算）单个文件的哈希 */
    private suspend fun fingerprintOf(path: String, file: File): String {
        val size = file.length()
        val modified = file.lastModified()

        dao.getByPath(path)?.let { cached ->
            // 长度与修改时间都没变 → 复用。两个都判，是因为只判
            // 修改时间会被「复制文件保留 mtime」骗过，
            // 只判长度则会把「改了一个字节」放过去
            if (cached.matches(size, modified) && cached.sha256.isNotBlank()) {
                return cached.sha256
            }
        }

        val hash = sha256Of(file)
        dao.upsert(
            ImageFingerprintEntity(
                imagePath = path,
                sha256 = hash,
                sizeBytes = size,
                modifiedAt = modified,
                computedAt = System.currentTimeMillis(),
            ),
        )
        return hash
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 只解文件头，不解整张图。
     *
     * `inJustDecodeBounds` 让解码器读完尺寸就返回，不分配像素内存 ——
     * 对一张 4000×3000 的照片，这是 0 字节 vs 48 MB 的差别。
     */
    private fun isDecodable(file: File): Boolean = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 && options.outHeight > 0
    } catch (_: Throwable) {
        // 有些 ROM 对损坏文件会抛而不是返回 null
        false
    }
}
