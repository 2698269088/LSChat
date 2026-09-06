package top.mcocet.server;

import org.java_websocket.WebSocket;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线连接注册中心：负责把新消息实时推送给已认证的 WebSocket 连接。
 * HTTP 模式与 WebSocket 模式发送消息时都会经过这里；
 * 当前没有 WS 连接（如纯 HTTP 模式）时推送为空操作，不影响原有轮询逻辑。
 */
public class RealtimeHub {

    private final Database database;
    private final ConcurrentHashMap<Long, Set<WebSocket>> byUser = new ConcurrentHashMap<>();

    public RealtimeHub(Database database) {
        this.database = database;
    }

    /** 注册某用户的在线连接（同一用户可有多端连接）。 */
    public void register(long userId, WebSocket connection) {
        byUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    /** 注销连接；该用户无其他在线连接时清理映射。 */
    public void unregister(long userId, WebSocket connection) {
        Set<WebSocket> connections = byUser.get(userId);
        if (connections != null && connections.remove(connection) && connections.isEmpty()) {
            byUser.remove(userId, connections);
        }
    }

    public int onlineCount() {
        return byUser.size();
    }

    /** 向指定用户的所有在线连接推送一条 JSON 消息。 */
    public void pushToUser(long userId, JSONObject payload) {
        Set<WebSocket> connections = byUser.get(userId);
        if (connections == null) {
            return;
        }
        String text = payload.toString();
        for (WebSocket connection : connections) {
            send(connection, text);
        }
    }

    /** 向群成员推送（可排除发送者本人，避免与其发送回执重复）。 */
    public void pushToGroup(long groupId, long excludeUserId, JSONObject payload) {
        for (MemberInfo member : database.groupMembers(groupId)) {
            if (member.userId() != excludeUserId) {
                pushToUser(member.userId(), payload);
            }
        }
    }

    private static void send(WebSocket connection, String text) {
        try {
            if (connection != null && connection.isOpen()) {
                connection.send(text);
            }
        } catch (Exception ignored) {
            // 单个连接发送失败不影响其他连接
        }
    }
}
