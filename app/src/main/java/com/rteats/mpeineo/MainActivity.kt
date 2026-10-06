package com.rteats.mpeineo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rteats.mpeineo.ui.MainViewModel
import com.rteats.mpeineo.ui.MpeiNeoApp
import com.rteats.mpeineo.ui.theme.MpeiNeoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MpeiNeoTheme {
                val application = application as MpeiNeoApplication
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(application.container))
                MpeiNeoApp(viewModel)
            }
        }
    }
}
