package com.plantidentify.ui.screens.addplant

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.plantidentify.ui.components.PhasePlaceholder

@Composable
fun AddPlantScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhasePlaceholder(
        modifier = modifier,
        title = "添加植物",
        phaseLabel = "Phase 2 · 图像采集",
        onBack = onBack,
        plannedItems = listOf(
            "CameraX 拍照（拒绝相机权限时自动改用相册，不阻断流程）",
            "系统 Photo Picker 从相册选择，targetSdk 36 下无需任何存储权限",
            "1–5 张多图管理：删除、替换、调整顺序",
            "每张图标注拍摄部位（整株 / 叶片 / 花 / 果实 / 茎干树皮 / 生境）",
            "原图完整保存到应用私有目录 filesDir，不使用 cacheDir",
            "生成 1536px / JPEG 80 的上传副本，并处理 EXIF 旋转",
        ),
    )
}
