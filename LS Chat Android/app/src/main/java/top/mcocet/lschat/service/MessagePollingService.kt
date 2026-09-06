package top.mcocet.lschat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.mcocet.lschat.MainActivity
import top.mcocet.lschat.R
import top.mcocet.lschat.data.ApiClient
import top.mcocet.lschat.data.Message
import top.mcocet.lschat.data.SessionManager

/**
 * 前台消息轮询服务：
 * - 以前台服务形式常驻，系统不会因回收内存而轻易杀死；
 * - 持有部分唤醒锁（每次轮询限时 10 秒），保证息屏后 CPU 仍可工作；
 * - 每 3 秒拉取一次发给自己的新消息并弹出系统通知；
 * - START_STICKY：进程被杀后系统会尝试自动重启服务。
 */
class MessagePollingService : Service() {

    companion object {
        private const val CHANNEL_SERVICE = "lschat_service"
        private const val CHANNEL_MESSAGES = "lschat_messages"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 3000L
        private const val BACKUP_POLL_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: SessionManager
    private lateinit var api: ApiClient
    private lateinit var wakeLock: PowerManager.WakeLock
    private var pollJob: Job? = null

    @Volatile
    private var lastSeen: Long = 0L

    @Volatile
    private var userNames: Map<Long, String> = emptyMap()

    @Volatile
    private var groupNames: Map<Long, String> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        session = SessionManager(this)
        api = ApiClient(session, serviceScope)
        runBlocking {
            api.host = session.serverHost() ?: ""
            api.port = session.serverPort()
            api.protocol = session.protocol()
            api.token = session.token() ?: ""
            api.pinnedCert = session.pinnedCert()
            lastSeen = session.lastSeenMessageId()
        }
        createChannels()
        ServiceCompat.startForeground(
            this,
            SERVICE_NOTIFICATION_ID,
            buildStatusNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
        )
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lschat:poll")
        wakeLock.setReferenceCounted(false)
        pollJob = serviceScope.launch {
            if (api.useWebSocket) webSocketLoop() else pollLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    /** HTTP 模式：定时轮询新消息。 */
    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            if (api.token.isEmpty() || api.host.isEmpty()) {
                stopSelf()
                return
            }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            try {
                handleIncoming(api.fetchIncoming(lastSeen))
            } catch (ignored: Exception) {
                // 网络异常时静默跳过本轮轮询
            } finally {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * WebSocket 模式：维持 WSS 长连接，实时推送立即通知；
     * 另每 30 秒兜底拉取一次，防止漏推；断线后自动重连。
     */
    private suspend fun webSocketLoop() {
        while (currentCoroutineContext().isActive) {
            if (api.token.isEmpty() || api.host.isEmpty()) {
                stopSelf()
                return
            }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            try {
                api.ensureWebSocket()
                val pushJob = serviceScope.launch {
                    api.messagePushes.collect { message -> handleIncoming(listOf(message)) }
                }
                try {
                    while (currentCoroutineContext().isActive && api.isWebSocketConnected()) {
                        handleIncoming(api.fetchIncoming(lastSeen))
                        delay(BACKUP_POLL_INTERVAL_MS)
                    }
                } finally {
                    pushJob.cancel()
                }
            } catch (ignored: Exception) {
                // 连接失败，稍后重试
            } finally {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 统一的新消息处理：更新已读位置并弹出通知。 */
    private suspend fun handleIncoming(incoming: List<Message>) {
        if (incoming.isEmpty()) return
        val fresh = incoming.filter { it.id > lastSeen }
        if (fresh.isEmpty()) return
        lastSeen = fresh.maxOf { it.id }
        session.saveLastSeenMessageId(lastSeen)
        notifyMessages(fresh)
    }

    private suspend fun notifyMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        // 发送者用户名缓存，遇到未知发送者时刷新一次
        val unknownSender = messages.any { !userNames.containsKey(it.from) }
        if (unknownSender) {
            runCatching { userNames = api.users().associate { it.id to it.username } }
        }
        // 群消息：缓存群名称，遇到未知群时刷新一次
        val unknownGroup = messages.any { it.group > 0 && !groupNames.containsKey(it.group) }
        if (unknownGroup) {
            runCatching {
                groupNames = api.myGroups().associate { it.id to it.name }
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        for (message in messages) {
            val sender = userNames[message.from] ?: "用户 #${message.from}"
            val text = if (message.type == Message.TYPE_IMAGE) "[图片]" else message.content
            val title = if (message.group > 0) {
                val groupName = groupNames[message.group] ?: "群 #${message.group}"
                "[$groupName] $sender"
            } else {
                sender
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(message.id.toInt(), notification)
        }
    }

    private fun buildStatusNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("LS Chat")
            .setContentText("正在后台接收消息")
            .setContentIntent(openPending)
            .setOngoing(true)
            .build()
    }

    private fun createChannels() {
        // 通知渠道仅 Android 8.0（API 26）及以上支持，低版本系统自动跳过
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "后台服务", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "新消息", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
