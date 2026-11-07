package com.dsh.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.dsh.mobile.ui.DshRoot

/**
 * Single-activity app. Routes:
 *  - VIEW http(s)…pair-accept?pair=… (opened from browser/QR apps)
 *  - SEND text/plain (shared links)
 *  - com.dsh.mobile.action.RECONNECT (app shortcut)
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DshRoot(vm) }
        consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> vm.handleViewUri(intent.data)
            Intent.ACTION_SEND ->
                if (intent.type == "text/plain") vm.handleIncoming(intent.getStringExtra(Intent.EXTRA_TEXT))
            ACTION_RECONNECT -> {
                val last = appGraph.connectionStore.mostRecent()
                if (last != null) vm.reconnect(last)
                else vm.toast("还没有已保存的连接")
            }
        }
    }

    companion object { const val ACTION_RECONNECT = "com.dsh.mobile.action.RECONNECT" }
}
