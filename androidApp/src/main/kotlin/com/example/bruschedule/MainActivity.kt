package com.example.bruschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.bruschedule.data.createAndroidAppGraph

class MainActivity : ComponentActivity() {
    private val appGraph by lazy(LazyThreadSafetyMode.NONE) {
        createAndroidAppGraph(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App(graph = appGraph)
        }
    }
}
