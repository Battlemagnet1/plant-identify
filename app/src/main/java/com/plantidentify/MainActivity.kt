package com.plantidentify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.plantidentify.ui.navigation.PlantIdentifyNavHost
import com.plantidentify.ui.screens.home.HomeViewModel
import com.plantidentify.ui.theme.PlantIdentifyTheme

/**
 * 单 Activity 承载全部 Compose 页面（规格书第二十七节）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as PlantIdentifyApplication).container

        setContent {
            PlantIdentifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // HomeViewModel 提升到 Activity 作用域，
                    // 让返回首页时统计不会重新订阅、列表滚动位置不丢。
                    val homeViewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(container.plantRepository),
                    )

                    PlantIdentifyNavHost(homeViewModel = homeViewModel)
                }
            }
        }
    }
}
