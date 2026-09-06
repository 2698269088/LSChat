package top.mcocet.lschat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "lschat")

/**
 * 本地会话存储（DataStore）：
 * - 服务器地址（首次启动配置）
 * - 登录令牌与当前用户（持久化登录）
 * - 已信任的服务器证书（TOFU 证书锁定，防中间人）
 */
class SessionManager(private val context: Context) {

    private val hostKey = stringPreferencesKey("server_host")
    private val portKey = longPreferencesKey("server_port")
    private val protocolKey = stringPreferencesKey("server_protocol")
    private val tokenKey = stringPreferencesKey("token")
    private val userIdKey = longPreferencesKey("user_id")
    private val userNameKey = stringPreferencesKey("user_name")
    private val pinnedCertKey = stringPreferencesKey("pinned_cert")
    private val lastSeenKey = longPreferencesKey("last_seen_message_id")

    suspend fun serverHost(): String? = context.dataStore.data.first()[hostKey]

    suspend fun serverPort(): Int = (context.dataStore.data.first()[portKey] ?: 18443L).toInt()

    /** 通信协议：websocket（默认，WSS 长连接实时推送）/ http（HTTPS 轮询）。 */
    suspend fun protocol(): String =
        context.dataStore.data.first()[protocolKey]?.takeIf { it == "http" || it == "websocket" }
            ?: "websocket"

    suspend fun saveServer(host: String, port: Int, protocol: String) {
        context.dataStore.edit {
            it[hostKey] = host
            it[portKey] = port.toLong()
            it[protocolKey] = protocol
        }
    }

    suspend fun token(): String? = context.dataStore.data.first()[tokenKey]

    suspend fun userId(): Long = context.dataStore.data.first()[userIdKey] ?: -1L

    suspend fun userName(): String? = context.dataStore.data.first()[userNameKey]

    suspend fun saveSession(token: String, userId: Long, userName: String) {
        context.dataStore.edit {
            it[tokenKey] = token
            it[userIdKey] = userId
            it[userNameKey] = userName
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(tokenKey)
            it.remove(userIdKey)
            it.remove(userNameKey)
        }
    }

    suspend fun pinnedCert(): String? = context.dataStore.data.first()[pinnedCertKey]

    suspend fun savePinnedCert(encoded: String) {
        context.dataStore.edit { it[pinnedCertKey] = encoded }
    }

    suspend fun clearPinnedCert() {
        context.dataStore.edit { it.remove(pinnedCertKey) }
    }

    /** 后台服务已通知/已读到的最大消息 id。 */
    suspend fun lastSeenMessageId(): Long = context.dataStore.data.first()[lastSeenKey] ?: 0L

    suspend fun saveLastSeenMessageId(id: Long) {
        context.dataStore.edit { it[lastSeenKey] = id }
    }
}
