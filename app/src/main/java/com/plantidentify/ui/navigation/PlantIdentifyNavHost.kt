package com.plantidentify.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.plantidentify.AppContainer
import com.plantidentify.ui.camera.CameraCaptureScreen
import com.plantidentify.ui.screens.addplant.AddPlantScreen
import com.plantidentify.ui.screens.addplant.AddPlantViewModel
import com.plantidentify.ui.screens.detail.PlantDetailScreen
import com.plantidentify.ui.screens.detail.PlantDetailViewModel
import com.plantidentify.ui.screens.home.HomeScreen
import com.plantidentify.ui.screens.home.HomeViewModel
import com.plantidentify.ui.screens.observation.ObservationScreen
import com.plantidentify.ui.screens.recognition.RecognitionScreen
import com.plantidentify.ui.screens.recognition.RecognitionViewModel
import com.plantidentify.ui.screens.search.SearchScreen
import com.plantidentify.ui.screens.settings.SettingsScreen
import com.plantidentify.ui.screens.settings.SettingsViewModel

/**
 * 导航图（规格书第二十七节）。
 *
 * 单 Activity + Compose Navigation，7 个页面全部注册，另加 Phase 2 的相机页。
 *
 * [container] 作为组合根被传进来：本应用不引入 DI 框架，
 * 由 Application 持有的 [AppContainer] 提供依赖，页面各自创建自己的 ViewModel。
 * HomeViewModel 例外 —— 它提升到 Activity 作用域（在 MainActivity 中创建），
 * 避免每次返回首页都重新订阅统计。
 */
@Composable
fun PlantIdentifyNavHost(
    homeViewModel: HomeViewModel,
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onAddPlant = { navController.navigateSingleTop(Routes.ADD_PLANT) },
                onSearch = { navController.navigateSingleTop(Routes.SEARCH) },
                onSettings = { navController.navigateSingleTop(Routes.SETTINGS) },
                onOpenPlantDetail = { plantId ->
                    navController.navigateSingleTop(Routes.plantDetail(plantId))
                },
                onOpenObservation = { observationId ->
                    navController.navigateSingleTop(Routes.observation(observationId))
                },
                onOpenRecognition = { navController.navigateSingleTop(Routes.RECOGNITION) },
                viewModel = homeViewModel,
            )
        }

        composable(Routes.ADD_PLANT) { backStackEntry ->
            val addPlantViewModel: AddPlantViewModel = viewModel(
                factory = AddPlantViewModel.factory(
                    imageStore = container.imageStore,
                    draftStore = container.captureDraftStore,
                    imageCompressor = container.imageCompressor,
                    externalScope = container.applicationScope,
                ),
            )

            val draft by addPlantViewModel.draft.collectAsStateWithLifecycle()
            val importing by addPlantViewModel.importing.collectAsStateWithLifecycle()
            val message by addPlantViewModel.message.collectAsStateWithLifecycle()
            val uploadPlan by addPlantViewModel.uploadPlan.collectAsStateWithLifecycle()

            // 相机页拍完回传的临时文件路径
            val capturedTempPath by backStackEntry.savedStateHandle
                .getStateFlow<String?>(Routes.KEY_CAPTURED_TEMP_PATH, null)
                .collectAsStateWithLifecycle()

            AddPlantScreen(
                draft = draft,
                importing = importing,
                message = message,
                uploadPlan = uploadPlan,
                imageStore = container.imageStore,
                capturedTempPath = capturedTempPath,
                onCapturedTempConsumed = {
                    backStackEntry.savedStateHandle.remove<String>(Routes.KEY_CAPTURED_TEMP_PATH)
                },
                onImportUris = addPlantViewModel::importUris,
                onImportCapture = addPlantViewModel::importCapture,
                onRemove = addPlantViewModel::remove,
                onMoveEarlier = addPlantViewModel::moveEarlier,
                onMoveLater = addPlantViewModel::moveLater,
                onSetRole = addPlantViewModel::setRole,
                onDiscardAll = {
                    addPlantViewModel.discardAll()
                    navController.popBackStack()
                },
                onMessageConsumed = addPlantViewModel::consumeMessage,
                onOpenCamera = { navController.navigateSingleTop(Routes.CAMERA) },
                onStartRecognition = { navController.navigateSingleTop(Routes.RECOGNITION) },
                onBack = navController::popBackStack,
            )
        }

        composable(Routes.CAMERA) {
            CameraCaptureScreen(
                onCaptured = { tempFile ->
                    // 把结果放回上一个条目（添加植物页），再由它导入正式目录
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(Routes.KEY_CAPTURED_TEMP_PATH, tempFile.absolutePath)
                    navController.popBackStack()
                },
                onCancel = navController::popBackStack,
            )
        }

        composable(Routes.RECOGNITION) {
            val recognitionViewModel: RecognitionViewModel = viewModel(
                factory = RecognitionViewModel.factory(
                    draftStore = container.captureDraftStore,
                    imageStore = container.imageStore,
                    imageCompressor = container.imageCompressor,
                    aiSettingsStore = container.aiSettingsStore,
                    visionProvider = container.visionProvider,
                    textProvider = container.textProvider,
                    repository = container.plantRepository,
                ),
            )

            RecognitionScreen(
                viewModel = recognitionViewModel,
                // 「添加更多照片」回到添加页继续加图；回来后重新进入本页会
                // 重新创建 ViewModel 并自动重跑识别（ViewModel 绑在导航条目上）
                onAddMorePhotos = navController::popBackStack,
                onOpenSettings = { navController.navigateSingleTop(Routes.SETTINGS) },
                onOpenPlantDetail = { plantId ->
                    // 保存成功后进入详情页，并把识别页从返回栈里摘掉 ——
                    // 否则用户从详情页返回会回到一个「已经保存过」的结果页，
                    // 再点一次保存就会产生重复档案
                    navController.navigate(Routes.plantDetail(plantId)) {
                        popUpTo(Routes.RECOGNITION) { inclusive = true }
                    }
                },
                onRetry = recognitionViewModel::recognize,
                onSave = recognitionViewModel::saveCurrentResult,
                onRegenerateAnalysis = recognitionViewModel::regenerateAnalysis,
                onBack = navController::popBackStack,
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    store = container.aiSettingsStore,
                    visionProvider = container.visionProvider,
                    textProvider = container.textProvider,
                ),
            )

            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = navController::popBackStack,
            )
        }

        composable(
            route = Routes.PLANT_DETAIL,
            arguments = listOf(
                navArgument(Routes.KEY_PLANT_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val plantId = backStackEntry.arguments?.getLong(Routes.KEY_PLANT_ID) ?: 0L

            val detailViewModel: PlantDetailViewModel = viewModel(
                factory = PlantDetailViewModel.factory(
                    plantId = plantId,
                    repository = container.plantRepository,
                    textProvider = container.textProvider,
                    aiSettingsStore = container.aiSettingsStore,
                ),
            )

            PlantDetailScreen(
                imageStore = container.imageStore,
                viewModel = detailViewModel,
                onBack = navController::popBackStack,
            )
        }

        composable(
            route = Routes.OBSERVATION,
            arguments = listOf(
                navArgument(Routes.KEY_OBSERVATION_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            ObservationScreen(
                observationId = backStackEntry.arguments
                    ?.getLong(Routes.KEY_OBSERVATION_ID) ?: 0L,
                onBack = navController::popBackStack,
            )
        }
    }
}

/** 避免快速连点造成同一页面被压入多次 */
private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}
