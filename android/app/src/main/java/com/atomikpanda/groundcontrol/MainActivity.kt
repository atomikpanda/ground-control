package com.atomikpanda.groundcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GroundControlTheme { GroundControlApp(this) } }
    }
}
