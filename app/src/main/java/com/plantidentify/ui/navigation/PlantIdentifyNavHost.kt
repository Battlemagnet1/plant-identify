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
import com.plantidentify.ui.screens.data.DataManagementScreen
import com.plantidentify.ui.screens.data.DataManagementViewModel
import com.plantidentify.ui.screens.edit.PlantEditScreen
import com.plantidentify.ui.screens.edit.PlantEditViewModel
import com.plantidentify.ui.screens.observation.ObservationViewModel
import com.plantidentify.ui.screens.plants.PlantListViewModel
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
import com.plantidentify.ui.screens.stats.StatsScreen
import com.plantidentify.ui.screens.stats.StatsViewModel

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
                onOpenStats = { navController.navigateSingleTop(Routes.STATS) },
                imageStore = container.imageStore,
                viewModel = homeViewModel,
            )
        }

        composable(Routes.ADD_PLANT) { backStackEntry ->
            val addPlantViewModel: AddPlantViewModel = viewModel(
                factory = AddPlantViewModel.factory(
                    imageStore = container.imageStore,
                    draftStore = container.captureDraftStore,
                    imageCompressor = container.imageCompressor,
                    locationSettingsStore = container.locationSettingsStore,
                    locationProvider = container.locationProvider,
                    externalScope = container.applicationScope,
                ),
            )

            val draft by addPlantViewModel.draft.collectAsStateWithLifecycle()
            val importing by addPlantViewModel.importing.collectAsStateWithLifecycle()
            val message by addPlantViewModel.message.collectAsStateWithLifecycle()
            val uploadPlan by addPlantViewModel.uploadPlan.collectAsStateWithLifecycle()
            val locationUi by addPlantViewModel.locationUi.collectAsStateWithLifecycle()
            val requestLocationPermission by
                addPlantViewModel.requestLocationPermission.collectAsStateWithLifecycle()
            val askLocation by addPlantViewModel.askLocation.collectAsStateWithLifecycle()

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
                askLocation = askLocation,
                locationUi = locationUi,
                requestLocationPermission = requestLocationPermission,
                onLocationAllowed = addPlantViewModel::onLocationAllowed,
                onLocationDenied = addPlantViewModel::onLocationDenied,
                onLocationPermissionDenied = addPlantViewModel::onLocationPermissionDenied,
                onLocationPermissionRequested = addPlantViewModel::consumeLocationPermissionRequest,
                onRetryLocation = addPlantViewModel::retryLocation,
                onCaptureLocation = addPlantViewModel::captureLocation,
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
                onAppendToExisting = recognitionViewModel::appendToExistingPlant,
                onCreateNewPlant = recognitionViewModel::createNewPlant,
                onSave = recognitionViewModel::saveCurrentResult,
                onRegenerateAnalysis = recognitionViewModel::regenerateAnalysis,
                onBack = navController::popBackStack,
            )
        }

        composable(Routes.SEARCH) {
            val plantListViewModel: PlantListViewModel = viewModel(
                factory = PlantListViewModel.factory(repository = container.plantRepository),
            )

            SearchScreen(
                onBack = navController::popBackStack,
                onOpenPlantDetail = { plantId ->
                    navController.navigateSingleTop(Routes.plantDetail(plantId))
                },
                imageStore = container.imageStore,
                viewModel = plantListViewModel,
            )
        }

        composable(Routes.STATS) {
            val statsViewModel: StatsViewModel = viewModel(
                factory = StatsViewModel.factory(repository = container.plantRepository),
            )

            StatsScreen(
                onBack = navController::popBackStack,
                viewModel = statsViewModel,
            )
        }

        composable(Routes.DATA_MANAGEMENT) {
            val dataViewModel: DataManagementViewModel = viewModel(
                factory = DataManagementViewModel.factory(
                    repository = container.plantRepository,
                    exporter = container.dataExporter,
                    backupManager = container.backupManager,
                    locationSettingsStore = container.locationSettingsStore,
                    locationProvider = container.locationProvider,
                ),
            )
            DataManagementScreen(
                onBack = navController::popBackStack,
                viewModel = dataViewModel,
            )
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
                onOpenDataManagement = {
                    navController.navigateSingleTop(Routes.DATA_MANAGEMENT)
                },
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
                mediaSaver = container.mediaSaver,
                viewModel = detailViewModel,
                onBack = navController::popBackStack,
                onEdit = { id -> navController.navigateSingleTop(Routes.plantEdit(id)) },
                onOpenObservations = { id ->
                    // 详情页的「查看所有观察」进的是这株植物的全部观察，
                    // 而 Routes.OBSERVATION 走的是单条观察 —— 两者参数不同，
                    // 这里复用同一个页面但换用 plantId 参数
                    navController.navigateSingleTop(Routes.plantObservations(id))
                },
                onDeleted = navController::popBackStack,
            )
        }

        composable(
            route = Routes.PLANT_EDIT,
            arguments = listOf(
                navArgument(Routes.KEY_PLANT_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val plantId = backStackEntry.arguments?.getLong(Routes.KEY_PLANT_ID) ?: 0L

            val editViewModel: PlantEditViewModel = viewModel(
                factory = PlantEditViewModel.factory(
                    plantId = plantId,
                    repository = container.plantRepository,
                ),
            )

            PlantEditScreen(
                imageStore = container.imageStore,
                viewModel = editViewModel,
                onBack = navController::popBackStack,
                // 保存成功后退回详情页，让用户立刻看到改动结果
                onSaved = navController::popBackStack,
            )
        }

        composable(
            route = Routes.OBSERVATION,
            arguments = listOf(
                navArgument(Routes.KEY_OBSERVATION_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val observationId = backStackEntry.arguments
                ?.getLong(Routes.KEY_OBSERVATION_ID) ?: 0L

            val observationViewModel: ObservationViewModel = viewModel(
                factory = ObservationViewModel.factory(
                    plantId = null,
                    observationId = observationId,
                    repository = container.plantRepository,
                    draftStore = container.captureDraftStore,
                ),
            )

            ObservationScreen(
                imageStore = container.imageStore,
                mediaSaver = container.mediaSaver,
                viewModel = observationViewModel,
                onBack = navController::popBackStack,
                // 补图重识别：草稿已装好，去添加页继续加图并重新识别
                onReanalysisStarted = {
                    navController.navigateSingleTop(Routes.ADD_PLANT)
                },
            )
        }

        composable(
            route = Routes.PLANT_OBSERVATIONS,
            arguments = listOf(
                navArgument(Routes.KEY_PLANT_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val plantId = backStackEntry.arguments?.getLong(Routes.KEY_PLANT_ID) ?: 0L

            val observationViewModel: ObservationViewModel = viewModel(
                factory = ObservationViewModel.factory(
                    plantId = plantId,
                    observationId = null,
                    repository = container.plantRepository,
                    draftStore = container.captureDraftStore,
                ),
            )

            ObservationScreen(
                imageStore = container.imageStore,
                mediaSaver = container.mediaSaver,
                viewModel = observationViewModel,
                onBack = navController::popBackStack,
                onReanalysisStarted = {
                    navController.navigateSingleTop(Routes.ADD_PLANT)
                },
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
