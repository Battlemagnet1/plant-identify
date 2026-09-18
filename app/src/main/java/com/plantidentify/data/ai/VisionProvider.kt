package com.plantidentify.data.ai

import com.plantidentify.data.local.entity.ImageRole
import java.io.File

/**
 * 待识别的一张图片。
 *
 * [file] 指向的是**压缩副本**（1536px / JPEG 80，由
 * [com.plantidentify.data.image.ImageCompressor] 生成），不是原图。
 * 原图完整保留在本地，从不用于上传。
 */
data class VisionImage(
    val file: File,
    val role: ImageRole = ImageRole.UNKNOWN,
)

/** 一次识别请求 */
data class VisionRequest(
    val images: List<VisionImage>,
    val config: AiEndpointConfig,
    val strategy: PromptStrategy = PromptStrategy.DEFAULT,
)

/** 一次测试连接请求 */
data class ConnectivityRequest(
    val config: AiEndpointConfig,
)

/**
 * 视觉识别通道。
 *
 * ## 为什么只有一个实现
 *
 * 首版预置 Qwen / 豆包 / OpenAI 三家（2026-09-18 决策），但它们都提供
 * OpenAI Compatible 端点。差异只在 Base URL 与模型名 —— 那是**数据**，
 * 不该写成三个类。因此这里只保留 [OpenAICompatibleVisionProvider] 一个实现，
 * 各家差异全部收敛到 [AiPreset] 的配置项里。
 *
 * 把接口留着而不是直接用具体类，是为了给「将来接入非 OpenAI 兼容的服务」
 * （例如 Pl@ntNet 的 REST 接口，其形态与本接口差异较大）留出位置。
 */
interface VisionProvider {

    /**
     * 执行多图联合识别。
     *
     * 实现约定：
     *  - **不抛异常**：所有失败都收敛到 [VisionCallResult.Failure]，
     *    由调用方决定如何展示
     *  - **保留原始文本**：即使解析失败，[VisionResponse.rawText] 也必须有值
     */
    suspend fun recognize(request: VisionRequest): VisionCallResult

    /**
     * 测试连接。
     *
     * 用于验证四件事：Base URL 是否正确、API Key 是否有效、
     * 模型名是否存在、以及该模型是否接受图片输入。
     */
    suspend fun testConnection(request: ConnectivityRequest): ConnectivityResult
}

/** 识别调用的结果 */
sealed interface VisionCallResult {

    data class Success(val response: VisionResponse) : VisionCallResult

    data class Failure(val failure: AiFailure) : VisionCallResult
}

/** 测试连接的结果 */
sealed interface ConnectivityResult {

    /**
     * @param model 服务端回显的模型名（可能与我们请求的一致，也可能是别名）
     * @param latencyMs 往返耗时
     */
    data class Success(
        val model: String,
        val latencyMs: Long,
        val acceptsImages: Boolean,
    ) : ConnectivityResult

    /**
     * 连接可用但图片不被接受 —— 说明配的是纯文本模型。
     * 单列一类是因为这是配置错误里最常见的一种，值得明确提示。
     */
    data class TextOnlyModel(val model: String) : ConnectivityResult

    data class Failure(val failure: AiFailure) : ConnectivityResult
}
