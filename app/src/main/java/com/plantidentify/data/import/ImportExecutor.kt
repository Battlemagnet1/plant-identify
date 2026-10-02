package com.plantidentify.data.import

import android.content.Context
import androidx.room.withTransaction
import com.plantidentify.data.backup.BackupManager
import com.plantidentify.data.backup.BackupPayload
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.FolderImportDataEntity
import com.plantidentify.data.local.entity.FolderImportItemEntity
import com.plantidentify.data.local.entity.FolderImportStatus
import com.plantidentify.data.local.entity.FolderType
import com.plantidentify.data.local.entity.ImageFingerprintEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.repository.FolderRepository
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.cleaning.RecordSnapshot
import com.plantidentify.domain.import.ImportMatchResult
import com.plantidentify.domain.import.ImportMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** 一次导入接收完成后的现场。 */
data class ImportSession(
    val folderId: Long,
    val sourceName: String,

    /** 这次导入了多少条植物 */
    val totalCount: Int,

    /** 每一条的判定结果 */
    val matchResult: ImportMatchResult,

    /** 新落盘的图片数 */
    val installedImages: Int,

    /** 内容与本地已有照片相同、因此**没有重复落盘**的图片数 */
    val reusedImages: Int,

    /** 包里有记录、包里却没有对应文件的图片数（包本身不完整时不为 0） */
    val missingImages: Int,
) {
    val pendingCount: Int get() = matchResult.pendingCount
}

/**
 * 数据导入的执行层（v1.0.2 Phase 2）。
 *
 * ## 与「从文件恢复」的本质区别（读这段之前先记住这一句）
 *
 * **恢复是整体替换（清空再写），导入是增量合并（绝不清空）。**
 * 恢复可以放心地把包里的 id 原样写回（库是空的，不会冲突）；
 * 导入**必须重新分配所有 id**，否则第一个 id 就撞上本地已有的档案。
 * 这也决定了图片的处理方式完全不同 —— 见 [installImages]。
 *
 * ## 两阶段：先接收，后合并
 *
 * [receive] 只做「把数据安全地放进一个协作文件夹」+「算出哪些可能重复」，
 * **一条合并都不执行**。用户看完整份清单、逐条确认之后，才轮到
 * [ImportMergeExecutor] 去合并 —— 需求原文要求「不要未经用户确认
 * 直接破坏性合并」，把它拆成两个方法是最直接的落实。
 */
class ImportExecutor(
    private val context: Context,
    private val database: PlantIdentifyDatabase,
    private val imageStore: ImageStore,
    private val backupManager: BackupManager,
    private val folderRepository: FolderRepository,
) {

    fun importDir(): File = File(context.filesDir, DIR_IMPORTS)

    /**
     * 接收一个数据包。
     *
     * 顺序是**先落图片、再写数据库**，与 `BackupManager.restore` 同一个理由：
     * 文件系统不参与 Room 事务，两件事只能按「宁可有孤儿文件，也不能有指向
     * 不存在文件的记录」来排。
     */
    suspend fun receive(
        packageFile: File,
        sourceName: String,
        originalFileName: String,
    ): Result<ImportSession> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = backupManager.readPayload(packageFile)
            require(payload.plants.isNotEmpty()) { "这个数据包里没有任何植物数据" }

            // 1) 图片先落地（判重、避让、写指纹）
            val imageOutcome = installImages(packageFile, payload)

            // 2) 建协作文件夹
            val folderName = sourceName.trim().ifEmpty { DEFAULT_FOLDER_NAME }
            val folderId = folderRepository.createFolder(
                name = folderName,
                type = FolderType.COLLABORATION,
                description = "从「$originalFileName」导入",
            ).getOrThrow()

            // 3) 插入三张表。**id 全部重新分配**（见类注释），
            //    同时把「旧 id → 新 id」记下来，观察与图片靠它重新挂对
            val plantIdMap = HashMap<Long, Long>()
            val observationIdMap = HashMap<Long, Long>()
            database.withTransaction {
                payload.plants.forEach { plant ->
                    plantIdMap[plant.id] = database.plantRecordDao().insert(plant.copy(id = 0L))
                }
                payload.observations.forEach { observation ->
                    // 找不到归属的观察直接跳过：包本身残缺时，
                    // 宁可少一条观察，也不要写出一条挂在不存在档案上的记录
                    val newPlantId = plantIdMap[observation.plantId] ?: return@forEach
                    observationIdMap[observation.id] = database.plantObservationDao()
                        .insert(observation.copy(id = 0L, plantId = newPlantId))
                }
                val newImages = payload.images.mapNotNull { image ->
                    val newObservationId = observationIdMap[image.observationId] ?: return@mapNotNull null
                    image.copy(
                        id = 0L,
                        observationId = newObservationId,
                        imagePath = imageOutcome.pathMap[image.imagePath] ?: image.imagePath,
                    )
                }
                if (newImages.isNotEmpty()) database.observationImageDao().insertAll(newImages)
            }

            val importPlantIds = plantIdMap.values.toList()

            // 4) 全部挂到协作文件夹。
            //    用 Phase 1 预留的 source 字段标出来源，便于日后按来源筛选
            folderRepository.addPlantsToFolder(folderId, importPlantIds)

            // 5) 跑匹配 —— 刚插入的那些要从「本地已有」里排除掉
            val importedSnapshots = payload.plants.map { it.toSnapshot(plantIdMap.getValue(it.id)) }
            val importIdSet = importPlantIds.toHashSet()
            val localSnapshots = database.plantRecordDao().getMatchSnapshots()
                .filter { it.id !in importIdSet }
                .map {
                    RecordSnapshot(
                        id = it.id,
                        name = it.name,
                        latinName = it.latinName,
                        family = it.family,
                        genus = it.genus,
                        description = it.description,
                    )
                }
            val matchResult = ImportMatcher.match(importedSnapshots, localSnapshots)

            // 6) 状态与元信息落库
            val now = System.currentTimeMillis()
            val rawPackage = archivePackage(packageFile)
            database.folderImportDao().upsertData(
                FolderImportDataEntity(
                    folderId = folderId,
                    sourceName = folderName,
                    importedAt = now,
                    sourceFileName = originalFileName,
                    totalCount = importPlantIds.size,
                    // 新记录与确定重复都算「已处理」——用户不需要为它们逐条点确认；
                    // 待处理的只有疑似与冲突
                    handledCount = matchResult.matches.count { !it.needsReview },
                    rawPackagePath = rawPackage,
                    lastMergedAt = null,
                ),
            )
            database.folderImportDao().insertItems(
                matchResult.matches.map { match ->
                    FolderImportItemEntity(
                        folderId = folderId,
                        importPlantId = match.importPlantId,
                        status = if (match.needsReview) {
                            FolderImportStatus.PENDING.name
                        } else {
                            FolderImportStatus.AUTO_NEW.name
                        },
                        matchedPlantId = match.matchedPlantId,
                        matchLevel = match.matchLevel,
                        decidedAt = if (match.needsReview) null else now,
                    )
                },
            )

            ImportSession(
                folderId = folderId,
                sourceName = folderName,
                totalCount = importPlantIds.size,
                matchResult = matchResult,
                installedImages = imageOutcome.installed,
                reusedImages = imageOutcome.reused,
                missingImages = payload.images.count { !imageStore.exists(it.imagePath) },
            )
        }
    }

    // ---------------------------------------------------------------- 图片

    private class ImageOutcome(
        val pathMap: Map<String, String>,
        val installed: Int,
        val reused: Int,
    )

    /**
     * 把包里的图片装进本地图片目录。
     *
     * ## 为什么不能像恢复那样直接解压
     *
     * 恢复时库已经被清空，图片目录里的文件全是上一批的孤儿 —— 覆盖、重名都无所谓。
     * 导入时本地照片**都还在用**，于是每一步都要小心：
     *
     * 1. **内容相同就不重复落盘**：先查 `image_fingerprint`（SHA-256），
     *    同一张照片换了个路径发过来时直接复用已有的那份
     * 2. **路径被占且内容不同就改名避让** —— 覆盖会毁掉本地照片
     * 3. **全程只增不删**：这个方法不会删除任何本地文件
     *
     * 返回的 `pathMap` 是「包里的路径 → 本地实际路径」，下一步写库要用它。
     */
    private suspend fun installImages(
        packageFile: File,
        payload: BackupPayload,
    ): ImageOutcome {
        val pathMap = HashMap<String, String>()
        if (payload.images.isEmpty()) return ImageOutcome(pathMap, 0, 0)

        val wanted = payload.images.map { it.imagePath }.toHashSet()
        // sha → 本地已有的路径。用来做「内容级」去重：
        // 同一张照片在别人库里叫 2026/01/a.jpg、在我这里叫 2026/03/b.jpg，
        // 按路径判断会漏掉，按内容才认得出来
        val knownByHash = HashMap<String, String>()
        runCatching {
            database.imageFingerprintDao().allPaths().forEach { path ->
                if (!imageStore.exists(path)) return@forEach
                val hash = database.imageFingerprintDao().getByPath(path)?.sha256 ?: return@forEach
                knownByHash.putIfAbsent(hash, path)
            }
        }

        val newFingerprints = mutableListOf<ImageFingerprintEntity>()
        var installed = 0
        var reused = 0

        ZipFile(packageFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("$DIR_IMAGES/") }
                .forEach { entry ->
                    val relative = entry.name.removePrefix("$DIR_IMAGES/")
                    // 包里带了库里没引用到的图片（比如用户删过档案留下的残图）——
                    // 只装我们要的那些，不要把无关文件一起搬进来
                    if (relative !in wanted) return@forEach

                    val hash = runCatching { sha256Of(zip, entry) }.getOrNull() ?: return@forEach

                    knownByHash[hash]?.let { existing ->
                        pathMap[relative] = existing
                        reused++
                        return@forEach
                    }

                    val occupied = imageStore.exists(relative)
                    val targetRelative = if (occupied) uniqueRelativePath(relative) else relative
                    val target = imageStore.resolve(targetRelative)
                    runCatching {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }.onSuccess {
                        pathMap[relative] = targetRelative
                        knownByHash[hash] = targetRelative
                        installed++
                        newFingerprints += ImageFingerprintEntity(
                            imagePath = targetRelative,
                            sha256 = hash,
                            sizeBytes = target.length(),
                            modifiedAt = target.lastModified(),
                            computedAt = System.currentTimeMillis(),
                        )
                    }
                }
        }

        // 新装的图片顺手写进指纹表 —— 不写的话，下一次数据清洗要为它们重算一遍哈希
        if (newFingerprints.isNotEmpty()) {
            runCatching { database.imageFingerprintDao().upsertAll(newFingerprints) }
        }

        return ImageOutcome(pathMap, installed, reused)
    }

    /**
     * 在文件名里插一段随机串避让重名。
     *
     * 插在扩展名之前（`a.jpg` → `a-3f2c9b1e.jpg`），不是加在后面 ——
     * 不然后缀会变成 `.jpg-3f2c...`，看图的应用认不出这是张图片。
     */
    private fun uniqueRelativePath(relative: String): String {
        val slash = relative.lastIndexOf('/')
        val dir = if (slash >= 0) relative.substring(0, slash) else ""
        val name = if (slash >= 0) relative.substring(slash + 1) else relative
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val unique = "$base-${UUID.randomUUID().toString().take(8)}$ext"
        return if (dir.isEmpty()) unique else "$dir/$unique"
    }

    /** 边读边算 SHA-256 —— 不把整张图读进内存（原图可能好几 MB） */
    private fun sha256Of(zip: ZipFile, entry: java.util.zip.ZipEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- 留档

    /**
     * 把原始数据包留一份。
     *
     * 需求要求「保留原始导入数据」。留档的意义是：用户事后想核对
     * 「张三当时到底发的是什么」，或者想重新导入同一批数据时，
     * 原件还在 —— 而不是只能对着一份被改造过的库发呆。
     *
     * @return 相对 `filesDir` 的路径；失败时返回 null（不影响导入本身）
     */
    private fun archivePackage(packageFile: File): String? = runCatching {
        val dir = importDir()
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "import_${System.currentTimeMillis()}.zip")
        packageFile.copyTo(target, overwrite = true)
        "$DIR_IMPORTS/${target.name}"
    }.getOrNull()

    private companion object {
        const val DIR_IMAGES = "images"
        const val DIR_IMPORTS = "imports"
        const val DEFAULT_FOLDER_NAME = "导入的数据"
    }
}

/**
 * 实体 → 匹配用快照。只带六级判定会用到的字段。
 *
 * `internal` 而不是 `private`：合并执行层（`ImportMergeExecutor`）也要用它
 * 把「保留的那条」与「并入的那条」喂给 `MergePlanner`。
 */
internal fun PlantRecordEntity.toSnapshot(id: Long) = RecordSnapshot(
    id = id,
    name = name,
    latinName = latinName,
    commonNames = commonNames,
    family = family,
    genus = genus,
    category = category,
    confidence = confidence,
    description = description,
)
