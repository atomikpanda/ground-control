package com.atomikpanda.groundcontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.NOTIFICATIONS_ENABLED
import com.atomikpanda.groundcontrol.data.PairLink
import com.atomikpanda.groundcontrol.data.settingsStore
import com.atomikpanda.groundcontrol.notify.WatchController
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingThread = MutableStateFlow<Pair<String, String>?>(null)

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            WatchController.enable(applicationContext)
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Toast.makeText(
                this,
                "Notifications are blocked for Ground Control. Enable them in system settings to get alerts.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val enabled = settingsStore.data.first()[NOTIFICATIONS_ENABLED] ?: false
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    WatchController.enable(applicationContext)
                }
            }
        }
        handleIntent(intent)
        setContent { GroundControlTheme { GroundControlApp(this, pendingThread) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val raw = intent?.data?.toString() ?: return
        PairLink.parse(raw)?.let { conn ->
            lifecycleScope.launch { ConnectionsRepository(applicationContext).upsert(conn) }
            return
        }
        lifecycleScope.launch {
            val conns = ConnectionsRepository(applicationContext).snapshot()
            when (val out = com.atomikpanda.groundcontrol.notify.DeepLinkResolver.resolve(raw, conns)) {
                is com.atomikpanda.groundcontrol.notify.DeepLinkOutcome.OpenThread ->
                    pendingThread.value = out.connectionId to out.threadId
                is com.atomikpanda.groundcontrol.notify.DeepLinkOutcome.AddConnection ->
                    Toast.makeText(
                        this@MainActivity,
                        "That workspace isn't connected on this device.",
                        Toast.LENGTH_LONG,
                    ).show()
                com.atomikpanda.groundcontrol.notify.DeepLinkOutcome.Ignore -> {}
            }
        }
    }
}
