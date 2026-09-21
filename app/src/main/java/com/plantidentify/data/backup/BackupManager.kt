package com.plantidentify.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.export.formatBytes
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 备份包里的清单，恢复前先读它做格式校验 */
data class BackupManifest(
    val app: String,
    val createdAt: Long,
    val plantCount: Int,
    val observationCount: Int,
    val imageCount: Int,
) {
    val createdAtText: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
}

/** 本机的一个备份包 */
data class BackupEntry(
    val file: File,
    /** 清单读取失败时为 null（文件损坏或不是本应用的包） */
    val manifest: BackupManifest?,
) {
    val fileName: String get() = file.name

    val sizeText: String get() = formatBytes(file.length())

    /** 列表上显示的一句话 */
    val summary: String
        get() = manifest?.let {
            "${it.plantCount} 株 · ${it.observationCount} 次观察 · ${it.imageCount} 张照片 · $sizeText"
        } ?: "无法读取（文件可能已损坏）· $sizeText"
}

data class BackupResult(
    val filePath: String,
    val fileName: String,
    val bytes: Long,
    val plantCount: Int,
    val observationCount: Int,
    val imageCount: Int,
)

data class RestoreResult(
    val plantCount: Int,
    val observationCount: Int,
    val imageCount: Int,
    /** 从包里还原出来的图片文件数 */
    val restoredFiles: Int,
    /** 包里缺失、库里却引用着的图片数（不为 0 说明备份包本身不完整） */
    val missingFiles: Int,
)

/**
 * 数据备份与恢复（规格书第二十二节）。
 *
 * ## 备份包格式
 *
 * ```
 * manifest.json   格式标识 + 版本 + 条数 + 时间（恢复前先读它校验）
 * data.json       三张表的全部字段，**含主键 id**
 * images/…        图片原文件，路径与库里的相对路径一致
 * ```
 *
 * ## 为什么是 JSON 而不是直接拷数据库文件
 *
 * 规格书要求「优先设计成可迁移的数据格式」。直接拷 `.db` 更简单，
 * 但 SQLite 文件带着版本号与页结构，将来加了字段、升了库版本，
 * 老备份就还原不进去；而 JSON 里字段是显式命名的，缺字段就取默认值，
 * 多字段就忽略 —— 跨版本、跨设备都能读。
 *
 * ## 为什么图片存相对路径
 *
 * 库里本来就只存相对路径（应用私有目录的绝对路径含随机沙盒路径，
 * 换机后必然失效）。备份沿用同一套相对路径，恢复时直接写回
 * `filesDir/images/` 即可，不需要做任何路径映射。
 */
class BackupManager(
    private val context: Context,
    private val database: PlantIdentifyDatabase,
    private val imageStore: ImageStore,
) {

    fun backupDir(): File = File(context.filesDir, DIR_BACKUPS)

    /**
     * 列出本机做过的备份包。
     *
     * ## 为什么必须有这个列表
     *
     * 备份落在应用私有目录（`filesDir/backups/`），而系统文件选择器**无权浏览**
     * 应用私有目录 —— 只提供「从文件恢复」的话，用户在本机做的备份反而恢复不了，
     * 只有先分享到网盘再选回来这一条路。列表补上「在本机直接恢复」。
     *
     * 跨设备迁移仍然走文件选择器：把备份分享出去，在另一台机器上选回来。
     */
    suspend fun listBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val dir = backupDir()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { file ->
                // 清单读不出来不丢弃这一项：让用户看得到、并在恢复时得到明确的报错，
                // 比「备份莫名其妙从列表里消失了」好
                BackupEntry(file = file, manifest = inspect(file).getOrNull())
            }
    }

    /** 删掉一个备份包 */
    suspend fun deleteBackup(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching { file.delete() }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 备份

    suspend fun backup(): Result<BackupResult> = withContext(Dispatchers.IO) {
        runCatching {
            val plants = database.plantRecordDao().getAll()
            val observations = database.plantObservationDao().getAll()
            val images = database.observationImageDao().getAll()

            val dir = backupDir()
            if (!dir.exists()) dir.mkdirs()

            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val file = File(dir, "${FILE_PREFIX}_$stamp.zip")

            var written = 0
            ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
                // 1) manifest
                zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zip.write(
                    JSONObject()
                        .put(FIELD_FORMAT, FORMAT_ID)
                        .put(FIELD_VERSION, FORMAT_VERSION)
                        .put(FIELD_APP, appName())
                        .put(FIELD_CREATED_AT, System.currentTimeMillis())
                        .put(
                            FIELD_COUNTS,
                            JSONObject()
                                .put(FIELD_PLANTS, plants.size)
                                .put(FIELD_OBSERVATIONS, observations.size)
                                .put(FIELD_IMAGES, images.size),
                        )
                        .toString(2)
                        .toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()

                // 2) data.json
                zip.putNextEntry(ZipEntry(ENTRY_DATA))
                zip.write(encodeData(plants, observations, images).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 3) 图片原文件。用流拷贝而不是 readBytes —— 原图可能上百 MB 总量，
                //    一次性读进内存没有必要
                images.map { it.imagePath }.distinct().forEach { path ->
                    val source = imageStore.resolve(path)
                    if (!source.isFile) return@forEach
                    runCatching {
                        zip.putNextEntry(ZipEntry("$DIR_IMAGES/$path"))
                        source.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                        written++
                    }
                }
            }

            BackupResult(
                filePath = file.absolutePath,
                fileName = file.name,
                bytes = file.length(),
                plantCount = plants.size,
                observationCount = observations.size,
                imageCount = written,
            )
        }
    }

    // ---------------------------------------------------------------- 读取清单

    /** 读备份包的清单，用于恢复前给用户看清楚「这一包是什么」 */
    /**
     * 把用户从文件选择器挑中的备份包复制到缓存，返回本地文件。
     *
     * 必须复制而不是直接沿用那个 Uri：SAF 给的 URI 只在本次授权期内有效，
     * 而恢复流程中间还夹着一个「确认对话框」，用户可能在上面停留很久。
     * 复制到缓存之后就对 URI 时效免疫了。
     */
    suspend fun importFromUri(uri: android.net.Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, DIR_IMPORTED)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, "restore_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选文件" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            require(target.length() > 0L) { "所选文件是空的" }
            target
        }
    }

    suspend fun inspect(file: File): Result<BackupManifest> = withContext(Dispatchers.IO) {
        runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(ENTRY_MANIFEST)
                    ?: error("这不是本应用导出的备份包（缺少 manifest.json）")
                val json = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val root = JSONObject(json)

                require(root.optString(FIELD_FORMAT) == FORMAT_ID) {
                    "这不是本应用的备份包"
                }
                val version = root.optInt(FIELD_VERSION, 0)
                require(version in 1..FORMAT_VERSION) {
                    "备份包版本为 $version，当前应用只支持到 $FORMAT_VERSION，请先升级应用"
                }

                val counts = root.optJSONObject(FIELD_COUNTS) ?: JSONObject()
                BackupManifest(
                    app = root.optString(FIELD_APP),
                    createdAt = root.optLong(FIELD_CREATED_AT),
                    plantCount = counts.optInt(FIELD_PLANTS),
                    observationCount = counts.optInt(FIELD_OBSERVATIONS),
                    imageCount = counts.optInt(FIELD_IMAGES),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 恢复备份。**替换**语义：现有档案会被整体覆盖。
     *
     * ## 为什么先落图片再写数据库
     *
     * 两件事没法放进同一个事务（文件系统不参与 Room 的事务）。
     * 顺序上宁可有「多余文件」也不能有「指向不存在文件的记录」：
     * 前者只是浪费空间，后者会让档案里的照片开天窗。
     * 所以先解图片，全部就位之后再提交数据库。
     */
    suspend fun restore(file: File): Result<RestoreResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = readPayload(file)

            // 1) 先还原图片文件
            var restoredFiles = 0
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("$DIR_IMAGES/") }
                    .forEach { entry ->
                        val relative = entry.name.removePrefix("$DIR_IMAGES/")
                        if (relative.isBlank()) return@forEach
                        val target = imageStore.resolve(relative)
                        target.parentFile?.mkdirs()
                        runCatching {
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            restoredFiles++
                        }
                    }
            }

            // 2) 再整体替换数据库内容
            database.withTransaction {
                database.observationImageDao().clearAll()
                database.plantObservationDao().clearAll()
                database.plantRecordDao().clearAll()

                // 清洗的三张表是**派生数据**，不随备份走，所以恢复后必须清空：
                //  · cleaning_issue 里的 recordIds 指向的是旧库的 id，
                //    不清的话用户会看到一批点进去「植物不存在」的待办
                //  · image_fingerprint 里的路径在新库里可能对应别的照片，
                //    不清会给出错误的内容哈希（比慢更糟：它会让「重复照片」判错）
                //  · cleaning_state 的游标若保留，下次扫描会以为「都查过了」，
                //    而库已经被整体换掉 —— 于是新的重复问题一个都发现不了
                database.cleaningIssueDao().clearAll()
                database.imageFingerprintDao().clearAll()
                database.cleaningStateDao()
                    .put(com.plantidentify.data.local.entity.CleaningStateEntity())

                payload.plants.forEach { database.plantRecordDao().insert(it) }
                payload.observations.forEach { database.plantObservationDao().insert(it) }
                // 图片行整体插入 —— 一次事务一次写入，比逐条快得多
                if (payload.images.isNotEmpty()) database.observationImageDao().insertAll(payload.images)
            }

            // 3) 清理不再被任何记录引用的孤儿文件
            //    （恢复前的旧档案已经不在了，它们引用的图片就是我刚才做的这批孤儿）
            pruneOrphanFiles()

            val missing = payload.images.count { !imageStore.exists(it.imagePath) }

            RestoreResult(
                plantCount = payload.plants.size,
                observationCount = payload.observations.size,
                imageCount = payload.images.size,
                restoredFiles = restoredFiles,
                missingFiles = missing,
            )
        }
    }

    /** 删除 filesDir/images 下没有被任何图片行引用的文件 */
    private suspend fun pruneOrphanFiles() {
        runCatching {
            // 引用集必须**同时**包含在途任务的图片。
            // 只算 observation_image 的话，用户一恢复备份，尚未开跑的识别任务
            // 的照片就会被当成孤儿文件删掉 —— 任务行还在、路径全成死链，
            // 而且不会有任何报错，只表现为「任务莫名其妙失败：照片读取失败」
            val referenced = (
                database.observationImageDao().getAll().map { it.imagePath } +
                    database.recognitionTaskImageDao().getAllPaths()
                ).toSet()
            val root = File(context.filesDir, DIR_IMAGES)
            if (!root.isDirectory) return
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (relative !in referenced) file.delete()
                }
        }
    }

    private fun appName(): String =
        runCatching {
            context.packageManager
                .getApplicationLabel(context.applicationInfo)
                .toString()
        }.getOrDefault("Plant Identify Library")

    // ---------------------------------------------------------------- 序列化

    private fun encodeData(
        plants: List<PlantRecordEntity>,
        observations: List<PlantObservationEntity>,
        images: List<ObservationImageEntity>,
    ): String = JSONObject()
        .put(
            FIELD_PLANTS,
            JSONArray().apply {
                plants.forEach { plant ->
                    put(
                        JSONObject()
                            .put(FIELD_ID, plant.id)
                            .put("name", plant.name)
                            .put("latinName", plant.latinName)
                            .put("commonNames", plant.commonNames)
                            .put("family", plant.family)
                            .put("genus", plant.genus)
                            .put("category", plant.category)
                            .put("confidence", plant.confidence)
                            .put("description", plant.description)
                            .put("morphologicalFeatures", plant.morphologicalFeatures)
                            .put("growthHabits", plant.growthHabits)
                            .put("floweringPeriod", plant.floweringPeriod)
                            .put("fruitingPeriod", plant.fruitingPeriod)
                            .put("landscapeUses", plant.landscapeUses)
                            .put("careAdvice", plant.careAdvice)
                            .put("pestControl", plant.pestControl)
                            .put("analysisStatus", plant.analysisStatus.name)
                            .put("note", plant.note)
                            .put("createdAt", plant.createdAt)
                            .put("updatedAt", plant.updatedAt)
                            // 回收站必须随备份走 —— 不带的话，用户在旧机上
                            // 删掉的植物会在新机恢复后「复活」，而回收站是空的
                            .put("deletedAt", plant.deletedAt),
                    )
                }
            },
        )
        .put(
            FIELD_OBSERVATIONS,
            JSONArray().apply {
                observations.forEach { observation ->
                    put(
                        JSONObject()
                            .put(FIELD_ID, observation.id)
                            .put("plantId", observation.plantId)
                            .put("timestamp", observation.timestamp)
                            .put("latitude", observation.latitude)
                            .put("longitude", observation.longitude)
                            .put("locationName", observation.locationName)
                            .put("note", observation.note)
                            .put("aiResultJson", observation.aiResultJson)
                            .put("isPrimary", observation.isPrimary),
                    )
                }
            },
        )
        .put(
            FIELD_IMAGES,
            JSONArray().apply {
                images.forEach { image ->
                    put(
                        JSONObject()
                            .put(FIELD_ID, image.id)
                            .put("observationId", image.observationId)
                            .put("imagePath", image.imagePath)
                            .put("role", image.role.name)
                            .put("sortOrder", image.sortOrder),
                    )
                }
            },
        )
        .toString()

    private class Payload(
        val plants: List<PlantRecordEntity>,
        val observations: List<PlantObservationEntity>,
        val images: List<ObservationImageEntity>,
    )

    private fun readPayload(file: File): Payload {
        val json = ZipFile(file).use { zip ->
            val entry = zip.getEntry(ENTRY_DATA)
                ?: error("备份包不完整（缺少 data.json）")
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val root = JSONObject(json)

        val plants = root.optJSONArray(FIELD_PLANTS).orEmpty().map { node ->
            PlantRecordEntity(
                id = node.getLong(FIELD_ID),
                name = node.getString("name"),
                latinName = node.optText("latinName"),
                commonNames = node.optText("commonNames"),
                family = node.optText("family"),
                genus = node.optText("genus"),
                category = node.optText("category"),
                confidence = node.optDouble("confidence", 0.0),
                description = node.optText("description"),
                morphologicalFeatures = node.optText("morphologicalFeatures"),
                growthHabits = node.optText("growthHabits"),
                floweringPeriod = node.optText("floweringPeriod"),
                fruitingPeriod = node.optText("fruitingPeriod"),
                landscapeUses = node.optText("landscapeUses"),
                careAdvice = node.optText("careAdvice"),
                pestControl = node.optText("pestControl"),
                // 枚举名认不出来就当没分析过，而不是让整个恢复失败
                analysisStatus = node.optEnum("analysisStatus", AnalysisStatus.NOT_REQUESTED),
                note = node.optText("note"),
                createdAt = node.optLong("createdAt"),
                updatedAt = node.optLong("updatedAt"),
                // 回收站随备份走（旧包没有这个字段 → null = 未删除）
                deletedAt = node.optLongOrNull("deletedAt"),
            )
        }

        val observations = root.optJSONArray(FIELD_OBSERVATIONS).orEmpty().map { node ->
            PlantObservationEntity(
                id = node.getLong(FIELD_ID),
                plantId = node.getLong("plantId"),
                timestamp = node.optLong("timestamp"),
                latitude = node.optDoubleOrNull("latitude"),
                longitude = node.optDoubleOrNull("longitude"),
                locationName = node.optText("locationName"),
                note = node.optText("note"),
                aiResultJson = node.optText("aiResultJson"),
                isPrimary = node.optBoolean("isPrimary", false),
            )
        }

        val images = root.optJSONArray(FIELD_IMAGES).orEmpty()
            .mapNotNull { node ->
                val path = node.optText("imagePath") ?: return@mapNotNull null
                ObservationImageEntity(
                    id = node.getLong(FIELD_ID),
                    observationId = node.getLong("observationId"),
                    imagePath = path,
                    role = node.optEnum("role", ImageRole.UNKNOWN),
                    sortOrder = node.optInt("sortOrder", 0),
                )
            }

        return Payload(plants, observations, images)
    }

    private companion object {
        const val DIR_BACKUPS = "backups"
        const val DIR_IMPORTED = "imported_backup"
        const val DIR_IMAGES = "images"

        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_DATA = "data.json"

        const val FORMAT_ID = "plant-identify-backup"
        const val FORMAT_VERSION = 1

        const val FILE_PREFIX = "plantIdentify_Backup"

        const val FIELD_FORMAT = "format"
        const val FIELD_VERSION = "version"
        const val FIELD_APP = "app"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_COUNTS = "counts"
        const val FIELD_PLANTS = "plants"
        const val FIELD_OBSERVATIONS = "observations"
        const val FIELD_IMAGES = "images"
        const val FIELD_ID = "id"
    }
}

/**
 * `optJSONArray` 在字段缺失时返回 null，转成空列表更省事 ——
 * 老备份包缺某张表是正常的，不该因此整体失败。
 */
private fun JSONArray?.orEmpty(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.let { add(it) }
        }
    }
}

/**
 * 取可空字符串。
 *
 * ⚠️ 必须用 `isNull` 判断：`JSONObject.optString(key)` 遇到 JSON 的 null
 * 会返回字符串 `"null"` 而不是 null，直接用它会让「没有学名」变成
 * 一个内容为 "null" 的学名。
 */
private fun JSONObject.optText(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/**
 * 可空 Long。`optLong` 对缺失键返回 0L —— 而 0 是合法的时间戳，
 * 用它当「没有值」会让未删除的档案被当成「1970 年删的」，
 * 所以必须像 optDoubleOrNull 一样显式判 has()。
 */
private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

/** 枚举按名字存取，认不出来就退回默认值 */
private inline fun <reified T : Enum<T>> JSONObject.optEnum(key: String, fallback: T): T {
    val name = optText(key) ?: return fallback
    return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}
