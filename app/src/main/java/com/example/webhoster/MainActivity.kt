package com.example.webhoster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.webhoster.ui.MainScreen
import com.example.webhoster.ui.MainViewModel
import com.example.webhoster.ui.theme.WebhosterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WebhosterTheme {
                // Get the instance of our ViewModel
                val viewModel: MainViewModel = viewModel()
                
                // Show our custom screen
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
