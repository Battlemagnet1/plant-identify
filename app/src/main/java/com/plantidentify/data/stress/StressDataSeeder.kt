package com.plantidentify.data.stress

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.withTransaction
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/**
 * 压测造数据器（Phase 4）。
 *
 * ## 为什么不是「跑一段 SQL 脚本」
 *
 * 造出来的数据必须**形状真实**，否则压不出真正的问题：
 * 外键要真实（否则 JOIN 的代价不对）、相对路径要真实且文件真的存在
 * （否则清洗会把每一张都报成「照片文件损坏」，问题表被假问题灌满）、
 * 时间戳要分散（全用同一个 `now` 会让排序与「最近观察」退化）。
 * 所以这里走**实体 + DAO**，而不是拼 SQL 字符串。
 *
 * ## 为什么照片放在 `stress/` 子目录
 *
 * 清理测试数据时要删掉上万张照片，而设备上同时存在用户的真实照片。
 * 把测试照片全部放进一个独立前缀下，清理就只是一次目录删除 ——
 * **按路径前缀删除是不可能误伤用户照片的**，而「把库里所有路径挨个删一遍」
 * 一旦库里混进真实记录就会删错东西。
 *
 * ## 照片为什么是 16×16 的小图
 *
 * 压测要压的是**行数与查询**（列表/搜索/统计/清洗扫描），不是图片字节。
 * 真实照片按压缩后约 200KB 算，10000 株就要 4GB —— 在模拟器上既写不下、
 * 也把时间全花在文件 IO 上。所以这里生成内容**各不相同**的小 PNG：
 * 各不相同是必须的，否则 sha256 相同，清洗会把它们全判成「照片内容重复」，
 * 于是放大出来的问题表全是这一类假问题。
 */
class StressDataSeeder(
    private val database: PlantIdentifyDatabase,
    private val imageStore: ImageStore,
) {

    /**
     * 造 [count] 株档案。
     *
     * @param onProgress 每批回调一次（已完成株数, 总数）。造 10000 株要几十秒，
     *        没有进度反馈的话界面看起来就是卡死了
     */
    suspend fun seed(
        count: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SeedReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val now = System.currentTimeMillis()

        var obsRows = 0
        var imgRows = 0
        var bytes = 0L

        var done = 0
        while (done < count) {
            val n = minOf(BATCH_PLANTS, count - done)
            val base = done

            // ---- ① 先在事务外把这一批的照片写完 ----
            //
            // 文件 IO 放进事务里会把事务拖到几百毫秒以上，而 SQLite 的事务是
            // 排他的 —— 那样「造数据」期间整个应用（包括界面读列表）都会被卡住，
            // 压测数据本身就成了干扰源。
            val files = ArrayList<Pair<String, Int>>(n * 2)
            for (k in 0 until n) {
                val index = base + k
                for (m in 0 until imageCountOf(index)) {
                    files += imagePathOf(index, m) to imageSeedOf(index, m)
                }
            }
            bytes += writeImages(files)

            // ---- ② 一个事务写完这一批的行 ----
            database.withTransaction {
                for (k in 0 until n) {
                    val index = base + k
                    val species = SPECIES[index % SPECIES.size]

                    // 名字分布刻意做成「长尾」：
                    // 七成是带序号的独特名（模拟用户记录不同个体），
                    // 三成落在 12 个池内名上（模拟**同一物种被反复记录** ——
                    // 这才是清洗算法真正要面对的输入，也是候选集压力的来源）
                    val unique = index % 10 < 7
                    val name = if (unique) "${species.name}${index + 1}" else species.name

                    // 让一部分档案缺字段，好让「缺失字段」规则有东西可报；
                    // 都补全的话那条规则在压测里等于没跑
                    val hasLatin = index % 7 != 0
                    val hasFamily = index % 11 != 0

                    val plantId = database.plantRecordDao().insert(
                        PlantRecordEntity(
                            name = name,
                            latinName = if (hasLatin) species.latin else null,
                            family = if (hasFamily) species.family else null,
                            genus = if (hasLatin) species.genus else null,
                            category = species.category,
                            confidence = 0.60 + (index % 40) / 100.0,
                            analysisStatus = if (index % 10 < 7) {
                                AnalysisStatus.SUCCEEDED
                            } else {
                                AnalysisStatus.NOT_REQUESTED
                            },
                            createdAt = now - index * 60_000L,
                            updatedAt = now - index * 60_000L,
                        ),
                    )

                    val observationCount = observationCountOf(index)
                    for (o in 0 until observationCount) {
                        // 观察时间也要分散：全部同一个时间戳会让
                        // 「按时间排序取代表观察」这类查询失去区分度
                        val timestamp = now - index * 60_000L - o * 6L * 60 * 60 * 1000

                        val observationId = database.plantObservationDao().insert(
                            PlantObservationEntity(
                                plantId = plantId,
                                timestamp = timestamp,
                                latitude = if (index % 5 == 0) 30.0 + (index % 100) / 1000.0 else null,
                                longitude = if (index % 5 == 0) 120.0 + (index % 100) / 1000.0 else null,
                                locationName = if (index % 5 == 0) "测试地点${index % 50}" else null,
                                note = if (index % 13 == 0) "压测记录 $index" else null,
                                // 给**合法** JSON：造一堆坏 JSON 会让清洗报满
                                // 「AI 原始结果损坏」，那是造数据引入的假问题，
                                // 不是被测代码的问题
                                aiResultJson = """{"name":"$name","confidence":0.9}""",
                                isPrimary = o == 0,
                            ),
                        )

                        val paths = (0 until imageCountOf(index, o)).map { m ->
                            imagePathOf(index, o, m)
                        }
                        database.observationImageDao().insertAll(
                            paths.mapIndexed { m, path ->
                                ObservationImageEntity(
                                    observationId = observationId,
                                    imagePath = path,
                                    role = ROLE_CYCLE[(index + m) % ROLE_CYCLE.size],
                                    sortOrder = m,
                                )
                            },
                        )
                        obsRows++
                        imgRows += paths.size
                    }
                }
            }

            done += n
            onProgress(done, count)
        }

        SeedReport(
            plants = count,
            observations = obsRows,
            images = imgRows,
            imageBytes = bytes,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * 清空**全部**业务数据。
     *
     * 压测要求每一档都从干净状态起跑 —— 上一档留下的 10000 株会让
     * 「100 条」这一档根本测不出 100 条的性能。所以这不是「可选步骤」。
     *
     * 覆盖所有表（含清洗与任务表）：漏掉清洗问题表的话，第二档会带着
     * 上一档算出来的问题行起跑，而 `insertIgnore` 又会让指纹相同的行
     * 保持旧状态，指标就不可比了。
     */
    suspend fun clearAll(): ClearReport = withContext(Dispatchers.IO) {
        val before = imageStore.usedBytes()

        // 顺序：先子表后主表。Room 的外键有级联，但显式删更清楚，
        // 也避免「级联是否生效」这种不确定性混进压测结果。
        // 各 DAO 的 clearAll() 返回 Unit（不是删除行数）——
        // 要行数就得给八张表各加一个 COUNT 查询，而压测关心的指标
        // 是「释放了多少空间」，行数在日志里看 SeedReport 就够
        database.withTransaction {
            database.observationImageDao().clearAll()
            database.plantObservationDao().clearAll()
            database.plantRecordDao().clearAll()
            database.cleaningIssueDao().clearAll()
            database.imageFingerprintDao().clearAll()
            database.cleaningStateDao().clearAll()
            database.recognitionTaskImageDao().clearAll()
            database.recognitionTaskDao().clearAll()
        }

        // 只删测试照片目录（见类注释：按前缀删才不会误伤用户照片）
        val stressDir = imageStore.resolve(STRESS_DIR)
        if (stressDir.exists()) stressDir.deleteRecursively()

        ClearReport(freedBytes = before - imageStore.usedBytes())
    }

    // ---------------- 内部 ----------------

    /** 每 250 株一个事务：批次小了进度条太碎，大了失败要用更多时间回滚 */
    private companion object {
        const val BATCH_PLANTS = 250

        /** 生成的小图边长（像素）。16×16 的 PNG 约 100 字节，够快也够唯一 */
        const val IMAGE_SIZE = 16

        val ROLE_CYCLE = arrayOf(
            ImageRole.WHOLE_PLANT, ImageRole.LEAF, ImageRole.FLOWER, ImageRole.FRUIT,
        )
    }

    private fun observationCountOf(index: Int): Int = 1 + (index * 7 + 3) % 3

    private fun imageCountOf(index: Int, observation: Int = 0): Int =
        1 + (index + observation * 5) % 2

    /**
     * 照片的相对路径。
     *
     * 分段（每 1000 株一个目录）是有意的：把 2 万多张照片平铺在一个目录里，
     * 模拟器的文件系统会明显变慢，而那是**造数据的产物**、不是被测代码的问题。
     */
    private fun imagePathOf(index: Int, observation: Int, image: Int = 0): String =
        "$STRESS_DIR/${index / 1000}/${index}_${observation}_$image.png"

    /** 每张图的唯一 seed —— 保证像素不同、sha256 不同 */
    private fun imageSeedOf(index: Int, observation: Int, image: Int = 0): Int =
        index * 31 + observation * 7 + image + 1

    private suspend fun writeImages(items: List<Pair<String, Int>>): Long = coroutineScope {
        items.chunked(256).map { chunk ->
            async(Dispatchers.IO) {
                var sum = 0L
                for ((relativePath, seed) in chunk) sum += writeOneImage(relativePath, seed)
                sum
            }
        }.awaitAll().sum()
    }

    private fun writeOneImage(relativePath: String, seed: Int): Long {
        val file = imageStore.resolve(relativePath)
        return runCatching {
            file.parentFile?.mkdirs()
            val bitmap = uniqueBitmap(seed)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            file.length()
        }.getOrDefault(0L)
    }

    /**
     * 生成一张内容唯一的小图。
     *
     * 前四个像素编码 [seed] 的四个字节，其余填底色。这样两张图的像素
     * **必然不同** → PNG 字节不同 → sha256 不同 → 不会被清洗判成重复照片。
     * （如果偷懒用同一张模板图复制 2 万份，清洗会报出 2 万条「照片内容重复」，
     *  问题表被这类假问题灌满，真正要压的候选集与判定反而淹没在里面。）
     */
    private fun uniqueBitmap(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(214, 228, 196))
        for (k in 0 until 4) {
            val value = (seed shr (k * 8)) and 0xFF
            bitmap.setPixel(
                k, 0,
                Color.rgb(value, 255 - value, (value * 7) and 0xFF),
            )
        }
        return bitmap
    }
}

/** 造数据的结果概览 */
data class SeedReport(
    val plants: Int,
    val observations: Int,
    val images: Int,
    val imageBytes: Long,
    val elapsedMs: Long,
)

/** 清库的结果概览 */
data class ClearReport(
    val freedBytes: Long,
)

/** 测试照片目录前缀（清理时按它整个删掉） */
const val STRESS_DIR = "stress"

/**
 * 用于造数据的物种池。
 *
 * 挑的是**真实的中国常见园林植物**，且刻意让它们分属不同科属 ——
 * 这样「同属名分桶」那条候选集路径才有真实的数据可走
 * （属名全一样或全不一样，那条路径都等于没测）。
 */
private val SPECIES = listOf(
    Species("紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属", "落叶灌木"),
    Species("香樟", "Cinnamomum camphora", "樟科", "樟属", "常绿乔木"),
    Species("桂花", "Osmanthus fragrans", "木樨科", "木樨属", "常绿灌木"),
    Species("银杏", "Ginkgo biloba", "银杏科", "银杏属", "落叶乔木"),
    Species("悬铃木", "Platanus acerifolia", "悬铃木科", "悬铃木属", "落叶乔木"),
    Species("杜鹃", "Rhododendron simsii", "杜鹃花科", "杜鹃属", "常绿灌木"),
    Species("月季", "Rosa chinensis", "蔷薇科", "蔷薇属", "常绿灌木"),
    Species("荷花", "Nelumbo nucifera", "莲科", "莲属", "水生草本"),
    Species("玉兰", "Magnolia denudata", "木兰科", "木兰属", "落叶乔木"),
    Species("石竹", "Dianthus chinensis", "石竹科", "石竹属", "多年生草本"),
    Species("爬山虎", "Parthenocissus tricuspidata", "葡萄科", "地锦属", "木质藤本"),
    Species("仙人掌", "Opuntia dillenii", "仙人掌科", "仙人掌属", "肉质植物"),
)

private data class Species(
    val name: String,
    val latin: String,
    val family: String,
    val genus: String,
    val category: String,
)
