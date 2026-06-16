package com.atomikpanda.groundcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.PairLink
import com.atomikpanda.groundcontrol.ui.theme.GroundControlTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink()
        setContent { GroundControlTheme { GroundControlApp(this) } }
    }

    /** Route a tapped/pasted `groundcontrol://add?...` link straight into the connections store. */
    private fun handleDeepLink() {
        val raw = intent?.data?.toString() ?: return
        val conn = PairLink.parse(raw) ?: return
        val repo = ConnectionsRepository(applicationContext)
        lifecycleScope.launch { repo.upsert(conn) }
    }
}
