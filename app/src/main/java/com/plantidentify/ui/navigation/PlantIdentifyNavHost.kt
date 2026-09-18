package com.plantidentify.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.plantidentify.ui.screens.addplant.AddPlantScreen
import com.plantidentify.ui.screens.detail.PlantDetailScreen
import com.plantidentify.ui.screens.home.HomeScreen
import com.plantidentify.ui.screens.home.HomeViewModel
import com.plantidentify.ui.screens.observation.ObservationScreen
import com.plantidentify.ui.screens.recognition.RecognitionScreen
import com.plantidentify.ui.screens.search.SearchScreen
import com.plantidentify.ui.screens.settings.SettingsScreen

/**
 * 导航图（规格书第二十七节）。
 *
 * 单 Activity + Compose Navigation，7 个页面全部注册。
 */
@Composable
fun PlantIdentifyNavHost(
    homeViewModel: HomeViewModel,
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

        composable(Routes.ADD_PLANT) {
            AddPlantScreen(onBack = navController::popBackStack)
        }

        composable(Routes.RECOGNITION) {
            RecognitionScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SEARCH) {
            SearchScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.PLANT_DETAIL,
            arguments = listOf(
                navArgument(Routes.KEY_PLANT_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            PlantDetailScreen(
                plantId = backStackEntry.arguments?.getLong(Routes.KEY_PLANT_ID) ?: 0L,
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
