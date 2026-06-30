package com.atomikpanda.groundcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.PairLink
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingThread = MutableStateFlow<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                else -> {}
            }
        }
    }
}
