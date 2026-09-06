package top.mcocet.lschat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.mcocet.lschat.data.SessionManager

/** 开机自启：设备重启后若此前已登录，自动恢复消息轮询服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = SessionManager(context)
                if (!session.token().isNullOrEmpty()) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, MessagePollingService::class.java)
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
