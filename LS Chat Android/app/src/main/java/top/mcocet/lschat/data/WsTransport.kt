package top.mcocet.lschat.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket（WSS）长连接传输层：
 * - 与 ApiClient 共用 OkHttpClient（含 TOFU 证书锁定的 TLS 配置），通信全程加密；
 * - 请求-响应按 req 序号关联（见服务端 WsChatServer 协议）；
 * - type=message 的推送帧进入 [pushes] 流；
 * - 连接断开时使所有未完成请求失败，由调用方重连。
 */
class WsTransport(
    private val client: OkHttpClient,
    @Volatile var host: String,
    @Volatile var port: Int,
    @Volatile var token: String
) {

    private val connectMutex = Mutex()
    private val nextReq = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    var connected: Boolean = false
        private set

    /** 服务端实时推送（type=message 帧）。 */
    val pushes = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)

    /** 确保已连接（未连接或已断开则建立新连接），握手时携带当前 token 认证。 */
    suspend fun ensureConnected() {
        if (connected) return
        connectMutex.withLock {
            if (connected) return
            val opened = CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url("wss://$host:$port/ws")
                .apply {
                    if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                }
                .build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val obj = JSONObject(text)
                        val req = if (obj.has("req")) obj.optLong("req") else 0L
                        val waiter = if (req > 0) pending.remove(req) else null
                        if (waiter != null) {
                            waiter.complete(obj)
                        } else if (obj.optString("type") == "message") {
                            pushes.tryEmit(obj)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onDisconnected()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onDisconnected()
                    opened.completeExceptionally(t)
                }
            })
            webSocket = ws
            opened.await()
        }
    }

    /** 发送指令并等待对应响应（20 秒超时）。 */
    suspend fun invoke(cmd: String, body: JSONObject = JSONObject()): JSONObject {
        ensureConnected()
        val id = nextReq.getAndIncrement()
        val payload = JSONObject(body.toString())
        payload.put("cmd", cmd)
        payload.put("req", id)
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val ws = webSocket ?: run {
            pending.remove(id)
            throw ApiException("WebSocket 未连接")
        }
        if (!ws.send(payload.toString())) {
            pending.remove(id)
            throw ApiException("WebSocket 发送失败")
        }
        return withTimeoutOrNull(20_000) { deferred.await() }
            ?: run {
                pending.remove(id)
                throw ApiException("请求超时")
            }
    }

    fun close() {
        connected = false
        webSocket?.close(1000, "client closing")
        webSocket = null
        failAllPending()
    }

    private fun onDisconnected() {
        connected = false
        webSocket = null
        failAllPending()
    }

    private fun failAllPending() {
        val error = ApiException("WebSocket 连接已断开")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}
