package top.mcocet.lschat.data

import android.annotation.SuppressLint
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class ApiException(message: String) : Exception(message)

/**
 * 网络接口封装，支持两种通信协议（均可选，默认 WebSocket）：
 * - websocket：WSS 长连接，JSON 指令 + 实时消息推送（见服务端 WsChatServer 协议）；
 * - http：HTTPS + JSON REST 接口，客户端轮询。
 * 两种协议均使用 TLS 加密，并采用 TOFU 证书锁定：
 * 首次连接时保存服务器证书，之后每次连接校验证书一致，防止中间人攻击。
 */
class ApiClient(
    private val session: SessionManager,
    private val scope: CoroutineScope
) {

    @Volatile
    var host: String = ""

    @Volatile
    var port: Int = 18443

    @Volatile
    var token: String = ""

    /** 通信协议：websocket（默认）/ http。 */
    @Volatile
    var protocol: String = "websocket"

    @Volatile
    var pinnedCert: String? = null

    val useWebSocket: Boolean
        get() = protocol == "websocket"

    // TOFU 证书锁定是有意为之的信任策略：首次连接保存服务器证书，之后严格校验一致性。
    // checkClientTrusted 对客户端（本应用不校验客户端证书）为空实现，服务端证书在 checkServerTrusted 中严格校验。
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val cert = chain[0]
            val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            val pinned = pinnedCert
            if (pinned == null) {
                pinnedCert = encoded
                scope.launch { session.savePinnedCert(encoded) }
            } else if (pinned != encoded) {
                throw CertificateException("服务器证书与首次连接时不一致，连接已被阻止")
            }
        }
    }

    private val client: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** WebSocket 传输层；host/port/token 在每次调用前由 [syncTransport] 同步。 */
    private val ws = WsTransport(client, host, port, token)

    /** WebSocket 模式下服务端实时推送的新消息。 */
    val messagePushes: Flow<Message> = ws.pushes
        .map { runCatching { Message.fromJson(it.getJSONObject("message")) }.getOrNull() }
        .filterNotNull()

    private fun baseUrl() = "https://$host:$port"

    /** 确保 WebSocket 连接已建立（WS 模式）。 */
    suspend fun ensureWebSocket() {
        if (!useWebSocket) return
        syncTransport()
        ws.ensureConnected()
    }

    fun isWebSocketConnected(): Boolean = useWebSocket && ws.connected

    fun closeWebSocket() = ws.close()

    private fun syncTransport() {
        ws.host = host
        ws.port = port
        ws.token = token
    }

    // ---------------- 统一指令入口 ----------------

    /**
     * 统一指令入口：WebSocket 模式走 WSS 长连接指令，HTTP 模式映射到对应 REST 接口。
     * 指令名与服务端 WsChatServer 一致。
     */
    private suspend fun invoke(cmd: String, body: JSONObject = JSONObject(), auth: Boolean = true): JSONObject {
        if (useWebSocket) {
            syncTransport()
            return ws.invoke(cmd, body)
        }
        return httpInvoke(cmd, body, auth)
    }

    private suspend fun httpInvoke(cmd: String, body: JSONObject, auth: Boolean): JSONObject = when (cmd) {
        "ping" -> request("/api/ping", "GET", auth = false)
        "register" -> request("/api/register", "POST", body, auth = false)
        "login" -> request("/api/login", "POST", body, auth = false)
        "users" -> request("/api/users", "GET")
        "avatar.get" -> request("/api/avatar?id=${body.optLong("id")}", "GET")
        "avatar.set" -> request("/api/avatar", "POST", body)
        "profile.get" -> request("/api/profile", "GET")
        "profile.set" -> request("/api/profile", "POST", body)
        "send" -> request("/api/messages", "POST", body)
        "poll" -> request("/api/messages?after=${body.optLong("after")}", "GET")
        "history" -> {
            val target =
                if (body.has("group")) "group=${body.optLong("group")}" else "peer=${body.optLong("peer")}"
            request("/api/messages?$target&after=${body.optLong("after")}", "GET")
        }
        "friends.lookup" -> request("/api/friends/lookup?id=${body.optLong("id")}", "GET")
        "friends.request" -> request("/api/friends/request", "POST", body)
        "friends.requests" -> request("/api/friends/requests", "GET")
        "friends.handle" -> request("/api/friends/requests", "POST", body)
        "groups" -> request("/api/groups", "GET")
        "groups.create" -> request("/api/groups/create", "POST", body)
        "groups.search" -> request("/api/groups/search?id=${body.optLong("id")}", "GET")
        "groups.join" -> request("/api/groups/join", "POST", body)
        "groups.requests" -> request("/api/groups/requests", "GET")
        "groups.handle" -> request("/api/groups/requests", "POST", body)
        "groups.rename" -> request("/api/groups/rename", "POST", body)
        "groups.settings" -> request("/api/groups/settings", "POST", body)
        "groups.mute" -> request("/api/groups/mute", "POST", body)
        "groups.role" -> request("/api/groups/role", "POST", body)
        "groups.members" -> request("/api/groups/members?groupId=${body.optLong("groupId")}", "GET")
        else -> throw ApiException("未知指令: $cmd")
    }

    private suspend fun request(
        path: String,
        method: String,
        jsonBody: JSONObject? = null,
        auth: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(baseUrl() + path)
        if (auth) {
            builder.header("Authorization", "Bearer $token")
        }
        if (jsonBody != null) {
            builder.method(method, jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.method(method, null)
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text.isNullOrEmpty()) {
                throw ApiException("连接服务器失败 (HTTP ${response.code})")
            }
            JSONObject(text)
        }
    }

    private fun JSONObject.checked(): JSONObject {
        val code = optInt("code", -1)
        if (code != 0) {
            throw ApiException(optString("message", "未知错误"))
        }
        return this
    }

    // ---------------- 基础 ----------------

    suspend fun ping(): String {
        val response = invoke("ping", auth = false)
        return "${response.optString("name")} v${response.optString("version")}"
    }

    suspend fun register(username: String, password: String): User {
        val body = JSONObject().put("username", username).put("password", password)
        val response = invoke("register", body, auth = false).checked()
        return User.fromJson(response.getJSONObject("user"))
    }

    suspend fun login(userId: Long, password: String): User {
        val body = JSONObject().put("userId", userId).put("password", password)
        val response = invoke("login", body, auth = false).checked()
        val user = User.fromJson(response.getJSONObject("user"))
        token = response.getString("token")
        session.saveSession(token, user.id, user.username)
        return user
    }

    // ---------------- 头像 ----------------

    /** 设置自己的头像（JPEG/PNG Base64）。 */
    suspend fun setAvatar(base64: String) {
        val body = JSONObject().put("content", base64)
        invoke("avatar.set", body).checked()
    }

    /** 取用户头像（Base64），未设置返回空串。 */
    suspend fun fetchAvatar(userId: Long): String {
        val response = invoke("avatar.get", JSONObject().put("id", userId)).checked()
        return response.optString("avatar", "")
    }

    // ---------------- 个人资料 ----------------

    /** 取自己的签名与状态。 */
    suspend fun getProfile(): Pair<String, String> {
        val response = invoke("profile.get").checked()
        val profile = response.getJSONObject("profile")
        return profile.optString("signature", "") to profile.optString("status", "")
    }

    /** 设置签名与状态。 */
    suspend fun setProfile(signature: String, status: String) {
        val body = JSONObject().put("signature", signature).put("status", status)
        invoke("profile.set", body).checked()
    }

    // ---------------- 用户与消息 ----------------

    suspend fun users(): List<User> {
        val response = invoke("users").checked()
        val array = response.getJSONArray("users")
        return (0 until array.length()).map { User.fromJson(array.getJSONObject(it)) }
    }

    suspend fun fetchMessages(peer: Long, after: Long): List<Message> {
        val body = JSONObject().put("peer", peer).put("after", after)
        val response = invoke("history", body).checked()
        val array = response.getJSONArray("messages")
        return (0 until array.length()).map { Message.fromJson(array.getJSONObject(it)) }
    }

    suspend fun fetchIncoming(after: Long): List<Message> {
        // 拉取所有发给我的新消息（后台轮询/兜底用）
        val response = invoke("poll", JSONObject().put("after", after)).checked()
        val array = response.getJSONArray("messages")
        return (0 until array.length()).map { Message.fromJson(array.getJSONObject(it)) }
    }

    suspend fun sendMessage(peer: Long, type: String, content: String): Message {
        val body = JSONObject().put("peer", peer).put("type", type).put("content", content)
        val response = invoke("send", body).checked()
        return Message.fromJson(response.getJSONObject("message"))
    }

    // ---------------- 好友 ----------------

    /** 按用户 ID 查找（添加好友前确认对方）。 */
    suspend fun lookupUser(id: Long): User {
        val response = invoke("friends.lookup", JSONObject().put("id", id)).checked()
        return User.fromJson(response.getJSONObject("user"))
    }

    /** 发送好友申请：返回提示（等待同意或已互为好友）。 */
    suspend fun sendFriendRequest(userId: Long): String {
        val response = invoke("friends.request", JSONObject().put("userId", userId)).checked()
        return response.optString("message", "已发送")
    }

    suspend fun friendRequests(): List<FriendRequest> {
        val response = invoke("friends.requests").checked()
        val array = response.getJSONArray("requests")
        return (0 until array.length()).map { FriendRequest.fromJson(array.getJSONObject(it)) }
    }

    suspend fun handleFriendRequest(requestId: Long, approve: Boolean) {
        val body = JSONObject().put("requestId", requestId).put("approve", approve)
        invoke("friends.handle", body).checked()
    }

    // ---------------- 群组 ----------------

    suspend fun myGroups(): List<GroupInfo> {
        val response = invoke("groups").checked()
        val array = response.getJSONArray("groups")
        return (0 until array.length()).map { GroupInfo.fromJson(array.getJSONObject(it)) }
    }

    suspend fun createGroup(name: String, joinPolicy: String): GroupInfo {
        val body = JSONObject().put("name", name).put("joinPolicy", joinPolicy)
        val response = invoke("groups.create", body).checked()
        return GroupInfo.fromJson(response.getJSONObject("group"))
    }

    suspend fun searchGroup(id: Long): GroupInfo {
        val response = invoke("groups.search", JSONObject().put("id", id)).checked()
        return GroupInfo.fromJson(response.getJSONObject("group"))
    }

    /** 加入群：返回提示（直接加入或已提交申请）。 */
    suspend fun joinGroup(groupId: Long): String {
        val response = invoke("groups.join", JSONObject().put("groupId", groupId)).checked()
        return response.optString("message", "已提交")
    }

    suspend fun groupRequests(): List<JoinRequest> {
        val response = invoke("groups.requests").checked()
        val array = response.getJSONArray("requests")
        return (0 until array.length()).map { JoinRequest.fromJson(array.getJSONObject(it)) }
    }

    suspend fun handleGroupRequest(requestId: Long, approve: Boolean) {
        val body = JSONObject().put("requestId", requestId).put("approve", approve)
        invoke("groups.handle", body).checked()
    }

    suspend fun renameGroup(groupId: Long, name: String) {
        val body = JSONObject().put("groupId", groupId).put("name", name)
        invoke("groups.rename", body).checked()
    }

    suspend fun updateGroupSettings(groupId: Long, joinPolicy: String, mutedAll: Boolean) {
        val body = JSONObject()
            .put("groupId", groupId)
            .put("joinPolicy", joinPolicy)
            .put("mutedAll", mutedAll)
        invoke("groups.settings", body).checked()
    }

    suspend fun setGroupMute(groupId: Long, userId: Long, muted: Boolean) {
        val body = JSONObject()
            .put("groupId", groupId)
            .put("userId", userId)
            .put("muted", muted)
        invoke("groups.mute", body).checked()
    }

    suspend fun setGroupRole(groupId: Long, userId: Long, role: String) {
        val body = JSONObject()
            .put("groupId", groupId)
            .put("userId", userId)
            .put("role", role)
        invoke("groups.role", body).checked()
    }

    suspend fun groupMembers(groupId: Long): List<GroupMember> {
        val response = invoke("groups.members", JSONObject().put("groupId", groupId)).checked()
        val array = response.getJSONArray("members")
        return (0 until array.length()).map { GroupMember.fromJson(array.getJSONObject(it)) }
    }

    suspend fun fetchGroupMessages(groupId: Long, after: Long): List<Message> {
        val body = JSONObject().put("group", groupId).put("after", after)
        val response = invoke("history", body).checked()
        val array = response.getJSONArray("messages")
        return (0 until array.length()).map { Message.fromJson(array.getJSONObject(it)) }
    }

    suspend fun sendGroupMessage(groupId: Long, type: String, content: String): Message {
        val body = JSONObject().put("group", groupId).put("type", type).put("content", content)
        val response = invoke("send", body).checked()
        return Message.fromJson(response.getJSONObject("message"))
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
