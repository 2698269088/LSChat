package top.mcocet.server;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LS Chat WebSocket 通信模式：WSS + JSON 指令，全程 TLS 加密。
 * 指令集与 HTTP 模式（{@link ChatServer}）的 REST 接口一一对应，
 * 客户端可通过统一入口在两种协议间切换。
 *
 * <p>通用约定：</p>
 * <ul>
 *   <li>连接建立时可带 {@code Authorization: Bearer <token>} 请求头完成认证；
 *   也可在连接后发 {@code {"cmd":"login"}} 或 {@code {"cmd":"auth","token":"..."}} 认证；</li>
 *   <li>每个请求可携带 {@code "req": <序号>}，响应原样回显该序号，用于请求-响应关联；</li>
 *   <li>响应统一为 {@code {"type": <指令名>, "code": 0, ...}}，错误为
 *   {@code {"type":"error", "cmd": ..., "code": ..., "message": ...}}；</li>
 *   <li>{@code type=message} 是服务端主动推送的新消息（无 req 字段）。</li>
 * </ul>
 *
 * <p>指令清单（与 HTTP 路径对照）：</p>
 * <ul>
 *   <li>ping / register / login / auth</li>
 *   <li>users（好友列表）；avatar.get{id} / avatar.set{content}；profile.get / profile.set</li>
 *   <li>send{peer|group,type,content}；poll{after}；history{peer|group,after}</li>
 *   <li>friends.lookup{id} / friends.request{userId} / friends.requests / friends.handle{requestId,approve}</li>
 *   <li>groups / groups.create / groups.search{id} / groups.join{groupId} / groups.requests /
 *   groups.handle / groups.rename / groups.settings / groups.mute / groups.role / groups.members</li>
 * </ul>
 */
public class WsChatServer extends WebSocketServer {

    /** 最大单帧大小：覆盖最大视频消息（Base64 后约 64MB），留足余量。 */
    private static final int MAX_FRAME_SIZE = 256 * 1024 * 1024;

    private final Database database;
    private final Auth auth;
    private final RealtimeHub hub;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ws-worker");
        thread.setDaemon(true);
        return thread;
    });
    /** 已认证连接 → 用户 ID。 */
    private final Map<WebSocket, Long> authedUsers = new ConcurrentHashMap<>();

    public WsChatServer(int port, Database database, Auth auth, RealtimeHub hub, SSLContext sslContext) {
        // 显式指定帧大小上限：默认 Draft 对超大帧抛 LimitExceededException 并断开连接
        super(new InetSocketAddress(port), 8, java.util.List.of(new Draft_6455(
                java.util.Collections.emptyList(),
                java.util.Collections.singletonList(new Protocol("")),
                MAX_FRAME_SIZE)));
        this.database = database;
        this.auth = auth;
        this.hub = hub;
        // 复用 HTTP 模式的自签名证书，启用 WSS（WebSocket over TLS）
        setWebSocketFactory(new DefaultSSLWebSocketServerFactory(sslContext));
        setConnectionLostTimeout(60);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        Long userId = resolveBearer(handshake.getFieldValue("Authorization"));
        if (userId != null) {
            authedUsers.put(connection, userId);
            hub.register(userId, connection);
            JSONObject welcome = new JSONObject();
            welcome.put("type", "welcome");
            welcome.put("userId", userId);
            send(connection, welcome);
        }
        System.out.println("[WsChatServer] 连接建立: " + connection.getRemoteSocketAddress()
                + (userId != null ? " (用户 " + userId + ")" : " (未认证)"));
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        // 串行处理，保证同一连接指令的顺序性，同时不阻塞 IO 线程
        worker.submit(() -> handleMessage(connection, message));
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        Long userId = authedUsers.remove(connection);
        if (userId != null) {
            hub.unregister(userId, connection);
        }
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        System.err.println("[WsChatServer] 连接异常: " + exception.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[WsChatServer] WSS 监听已启动，等待连接...");
    }

    /** 停止服务：关闭所有连接与处理线程。 */
    public void shutdown() {
        try {
            stop(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        worker.shutdown();
    }

    // ---------------- 指令分发 ----------------

    private void handleMessage(WebSocket connection, String message) {
        JSONObject body;
        try {
            body = new JSONObject(message.trim());
        } catch (Exception e) {
            send(connection, error(null, 400, "请求不是合法的 JSON"));
            return;
        }
        String cmd = body.optString("cmd", "");
        JSONObject response;
        try {
            response = dispatch(connection, cmd, body);
        } catch (ApiException e) {
            response = error(cmd, e.code(), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            response = error(cmd, 500, "服务器内部错误");
        }
        if (body.has("req")) {
            response.put("req", body.opt("req"));
        }
        send(connection, response);
    }

    private JSONObject dispatch(WebSocket connection, String cmd, JSONObject body) {
        Long selfId = authedUsers.get(connection);
        return switch (cmd) {
            case "ping" -> handlePingCmd();
            case "register" -> handleRegisterCmd(body);
            case "login" -> handleLoginCmd(connection, body);
            case "auth" -> handleAuthCmd(connection, body);
            case "users" -> handleUsersCmd(requireAuth(cmd, selfId));
            case "avatar.get" -> handleAvatarGetCmd(requireAuth(cmd, selfId), body);
            case "avatar.set" -> handleAvatarSetCmd(requireAuth(cmd, selfId), body);
            case "profile.get" -> handleProfileGetCmd(requireAuth(cmd, selfId));
            case "profile.set" -> handleProfileSetCmd(requireAuth(cmd, selfId), body);
            case "send" -> handleSendCmd(connection, requireAuth(cmd, selfId), body);
            case "poll" -> handlePollCmd(requireAuth(cmd, selfId), body);
            case "history" -> handleHistoryCmd(requireAuth(cmd, selfId), body);
            case "friends.lookup" -> handleFriendLookupCmd(requireAuth(cmd, selfId), body);
            case "friends.request" -> handleFriendRequestSendCmd(requireAuth(cmd, selfId), body);
            case "friends.requests" -> handleFriendRequestListCmd(requireAuth(cmd, selfId));
            case "friends.handle" -> handleFriendRequestHandleCmd(requireAuth(cmd, selfId), body);
            case "groups" -> handleGroupListCmd(requireAuth(cmd, selfId));
            case "groups.create" -> handleGroupCreateCmd(requireAuth(cmd, selfId), body);
            case "groups.search" -> handleGroupSearchCmd(requireAuth(cmd, selfId), body);
            case "groups.join" -> handleGroupJoinCmd(requireAuth(cmd, selfId), body);
            case "groups.requests" -> handleGroupRequestsCmd(requireAuth(cmd, selfId));
            case "groups.handle" -> handleGroupRequestHandleCmd(requireAuth(cmd, selfId), body);
            case "groups.rename" -> handleGroupRenameCmd(requireAuth(cmd, selfId), body);
            case "groups.settings" -> handleGroupSettingsCmd(requireAuth(cmd, selfId), body);
            case "groups.mute" -> handleGroupMuteCmd(requireAuth(cmd, selfId), body);
            case "groups.role" -> handleGroupRoleCmd(requireAuth(cmd, selfId), body);
            case "groups.members" -> handleGroupMembersCmd(requireAuth(cmd, selfId), body);
            default -> throw new ApiException(400, "未知指令: " + cmd);
        };
    }

    private static long requireAuth(String cmd, Long selfId) {
        if (selfId == null) {
            throw new ApiException(401, "未登录或登录已过期");
        }
        return selfId;
    }

    private static JSONObject reply(String cmd) {
        JSONObject response = new JSONObject();
        response.put("type", cmd);
        response.put("code", 0);
        return response;
    }

    private static JSONObject error(String cmd, int code, String message) {
        JSONObject error = new JSONObject();
        error.put("type", "error");
        if (cmd != null && !cmd.isEmpty()) {
            error.put("cmd", cmd);
        }
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    // ---------------- 基础指令 ----------------

    private JSONObject handlePingCmd() {
        JSONObject pong = new JSONObject();
        pong.put("type", "pong");
        pong.put("code", 0);
        pong.put("name", "LS Chat Server");
        pong.put("version", "1.0");
        pong.put("time", System.currentTimeMillis());
        return pong;
    }

    private JSONObject handleRegisterCmd(JSONObject body) {
        User user = auth.register(body.optString("username", "").trim(),
                body.optString("password", ""));
        return reply("register").put("user", user.toJson());
    }

    private JSONObject handleLoginCmd(WebSocket connection, JSONObject body) {
        long userId = body.optLong("userId", -1);
        if (userId < 0) {
            throw new ApiException(400, "请输入用户 ID");
        }
        String token = auth.login(userId, body.optString("password", ""));
        long loggedInId = database.resolveToken(token);
        UserRow row = database.findUserById(loggedInId);
        // 同一连接重复登录：先注销旧身份
        Long previous = authedUsers.put(connection, loggedInId);
        if (previous != null && previous != loggedInId) {
            hub.unregister(previous, connection);
        }
        hub.register(loggedInId, connection);
        return reply("login")
                .put("token", token)
                .put("user", new User(row.id(), row.username(), "", "").toJson());
    }

    private JSONObject handleAuthCmd(WebSocket connection, JSONObject body) {
        Long userId = database.resolveToken(body.optString("token", ""));
        if (userId == null) {
            throw new ApiException(401, "令牌无效或已过期");
        }
        Long previous = authedUsers.put(connection, userId);
        if (previous != null && previous != userId) {
            hub.unregister(previous, connection);
        }
        hub.register(userId, connection);
        JSONObject welcome = new JSONObject();
        welcome.put("type", "welcome");
        welcome.put("code", 0);
        welcome.put("userId", userId);
        return welcome;
    }

    // ---------------- 用户 / 头像 / 资料 ----------------

    private JSONObject handleUsersCmd(long selfId) {
        JSONArray array = new JSONArray();
        for (User user : database.friendsOf(selfId)) {
            array.put(user.toJson());
        }
        return reply("users").put("users", array);
    }

    private JSONObject handleAvatarGetCmd(long selfId, JSONObject body) {
        long id = body.optLong("id", 0);
        return reply("avatar.get").put("avatar", database.avatarOf(id));
    }

    private JSONObject handleAvatarSetCmd(long selfId, JSONObject body) {
        String content = body.optString("content", "");
        String invalid = MessageContent.validateAvatar(content);
        if (invalid != null) {
            throw new ApiException(400, invalid);
        }
        database.setAvatar(selfId, content);
        return reply("avatar.set");
    }

    private JSONObject handleProfileGetCmd(long selfId) {
        return reply("profile.get").put("profile", database.profileOf(selfId).toJson());
    }

    private JSONObject handleProfileSetCmd(long selfId, JSONObject body) {
        String signature = body.optString("signature", "").trim();
        String status = body.optString("status", "").trim();
        if (signature.length() > MessageContent.MAX_SIGNATURE_LENGTH) {
            throw new ApiException(400, "签名最多 " + MessageContent.MAX_SIGNATURE_LENGTH + " 字");
        }
        if (status.length() > MessageContent.MAX_STATUS_LENGTH) {
            throw new ApiException(400, "状态最多 " + MessageContent.MAX_STATUS_LENGTH + " 字");
        }
        database.setProfile(selfId, signature, status);
        return reply("profile.set");
    }

    // ---------------- 消息 ----------------

    private JSONObject handleSendCmd(WebSocket connection, long selfId, JSONObject body) {
        if (body.has("group")) {
            return sendToGroup(connection, selfId, body);
        }
        long peer = body.optLong("peer", -1);
        String type = body.optString("type", "");
        String content = body.optString("content", "");
        if (peer < 0 || database.findUserById(peer) == null) {
            throw new ApiException(400, "接收者不存在");
        }
        String invalid = MessageContent.validate(type, content);
        if (invalid != null) {
            throw new ApiException(400, invalid);
        }
        ChatMessage message = database.addMessage(selfId, peer, 0, type, content);
        // 私聊：实时推送给接收者（回执已给发送者，避免重复）
        if (peer != selfId) {
            hub.pushToUser(peer, messageEnvelope(message));
        }
        JSONObject ack = reply("send");
        ack.put("type", "ack");
        ack.put("message", message.toJson());
        return ack;
    }

    /** 群消息发送：校验成员身份与禁言状态（与 HTTP 模式一致）。 */
    private JSONObject sendToGroup(WebSocket connection, long selfId, JSONObject body) {
        long groupId = body.optLong("group", -1);
        String type = body.optString("type", "");
        String content = body.optString("content", "");
        GroupRow group = groupId < 0 ? null : database.findGroup(groupId);
        if (group == null) {
            throw new ApiException(404, "群组不存在");
        }
        String role = database.groupRole(groupId, selfId);
        if (role == null) {
            throw new ApiException(403, "你不是该群成员");
        }
        if (group.mutedAll() && "member".equals(role)) {
            throw new ApiException(403, "群主已开启全员禁言");
        }
        if (database.groupMemberMuted(groupId, selfId)) {
            throw new ApiException(403, "你已被管理员禁言");
        }
        String invalid = MessageContent.validate(type, content);
        if (invalid != null) {
            throw new ApiException(400, invalid);
        }
        ChatMessage message = database.addMessage(selfId, 0, groupId, type, content);
        hub.pushToGroup(groupId, selfId, messageEnvelope(message));
        JSONObject ack = reply("send");
        ack.put("type", "ack");
        ack.put("message", message.toJson());
        return ack;
    }

    private JSONObject handlePollCmd(long selfId, JSONObject body) {
        long after = body.optLong("after", 0);
        List<Long> myGroups = new java.util.ArrayList<>();
        for (GroupRow group : database.groupsOf(selfId)) {
            myGroups.add(group.id());
        }
        List<ChatMessage> list = database.incomingMessages(selfId, myGroups, after, 200);
        return reply("poll").put("messages", messagesToJson(list));
    }

    private JSONObject handleHistoryCmd(long selfId, JSONObject body) {
        long after = body.optLong("after", 0);
        List<ChatMessage> list;
        if (body.has("group")) {
            long groupId = body.optLong("group", -1);
            if (database.groupRole(groupId, selfId) == null) {
                throw new ApiException(403, "你不是该群成员");
            }
            list = database.groupMessages(groupId, after, 200);
        } else if (body.has("peer")) {
            long peer = body.optLong("peer", -1);
            list = database.messagesBetween(selfId, peer, after, 200);
        } else {
            throw new ApiException(400, "缺少 peer 或 group 参数");
        }
        return reply("history").put("messages", messagesToJson(list));
    }

    // ---------------- 好友 ----------------

    private JSONObject handleFriendLookupCmd(long selfId, JSONObject body) {
        long id = body.optLong("id", 0);
        UserRow user = id <= 0 ? null : database.findUserById(id);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        return reply("friends.lookup")
                .put("user", new User(user.id(), user.username(), "", "").toJson());
    }

    /** 发送好友申请：对方已向我申请过则直接互为好友。 */
    private JSONObject handleFriendRequestSendCmd(long selfId, JSONObject body) {
        long userId = body.optLong("userId", -1);
        UserRow target = userId < 0 ? null : database.findUserById(userId);
        if (target == null) {
            throw new ApiException(404, "用户不存在");
        }
        if (userId == selfId) {
            throw new ApiException(400, "不能添加自己为好友");
        }
        if (database.areFriends(selfId, userId)) {
            throw new ApiException(400, "对方已经是你的好友");
        }
        if (database.hasFriendRequest(selfId, userId)) {
            throw new ApiException(400, "已发送过申请，等待对方同意");
        }
        if (database.hasFriendRequest(userId, selfId)) {
            // 对方已向我发过申请：直接互为好友，并清理双方的申请记录
            database.addFriendship(selfId, userId);
            FriendRequestInfo pending = database.pendingFriendRequests(selfId).stream()
                    .filter(r -> r.userId() == userId).findFirst().orElse(null);
            if (pending != null) database.deleteFriendRequest(pending.id());
            return reply("friends.request").put("message", "对方已向你发送过申请，现已互为好友");
        }
        database.createFriendRequest(selfId, userId);
        return reply("friends.request").put("message", "已发送好友申请，等待对方同意");
    }

    private JSONObject handleFriendRequestListCmd(long selfId) {
        JSONArray array = new JSONArray();
        for (FriendRequestInfo request : database.pendingFriendRequests(selfId)) {
            array.put(request.toJson());
        }
        return reply("friends.requests").put("requests", array);
    }

    /** 处理发给我的好友申请：同意建立双向好友关系，拒绝则删除申请。 */
    private JSONObject handleFriendRequestHandleCmd(long selfId, JSONObject body) {
        long requestId = body.optLong("requestId", -1);
        boolean approve = body.optBoolean("approve", false);
        FriendRequestInfo request = requestId < 0 ? null : database.findFriendRequest(requestId);
        if (request == null) {
            throw new ApiException(404, "申请不存在或已处理");
        }
        if (!database.friendRequestBelongsTo(requestId, selfId)) {
            throw new ApiException(403, "无权处理该申请");
        }
        if (approve) {
            database.addFriendship(selfId, request.userId());
        }
        database.deleteFriendRequest(requestId);
        return reply("friends.handle");
    }

    // ---------------- 群组 ----------------

    private JSONObject groupToJson(GroupRow group, String myRole) {
        JSONObject object = new JSONObject();
        object.put("id", group.id());
        object.put("name", group.name());
        object.put("ownerId", group.ownerId());
        object.put("joinPolicy", group.joinPolicy());
        object.put("mutedAll", group.mutedAll());
        object.put("memberCount", database.groupMemberCount(group.id()));
        object.put("role", myRole == null ? JSONObject.NULL : myRole);
        return object;
    }

    /** 校验群主/管理员权限并返回 groupId，无权限时抛异常。 */
    private long requireManager(long selfId, JSONObject body) {
        long groupId = body.optLong("groupId", -1);
        GroupRow group = groupId < 0 ? null : database.findGroup(groupId);
        if (group == null) {
            throw new ApiException(404, "群组不存在");
        }
        String role = database.groupRole(groupId, selfId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw new ApiException(403, "需要群主或管理员权限");
        }
        return groupId;
    }

    private JSONObject handleGroupListCmd(long selfId) {
        JSONArray array = new JSONArray();
        for (GroupRow group : database.groupsOf(selfId)) {
            array.put(groupToJson(group, database.groupRole(group.id(), selfId)));
        }
        return reply("groups").put("groups", array);
    }

    private JSONObject handleGroupCreateCmd(long selfId, JSONObject body) {
        String name = body.optString("name", "").trim();
        String joinPolicy = body.optString("joinPolicy", "approval");
        if (name.isEmpty() || name.length() > 32) {
            throw new ApiException(400, "群名称需为 1-32 个字符");
        }
        if (!"open".equals(joinPolicy) && !"approval".equals(joinPolicy)) {
            throw new ApiException(400, "入群方式不合法");
        }
        long groupId = database.createGroup(name, selfId, joinPolicy);
        database.addGroupMember(groupId, selfId, "owner");
        return reply("groups.create")
                .put("group", groupToJson(database.findGroup(groupId), "owner"));
    }

    private JSONObject handleGroupSearchCmd(long selfId, JSONObject body) {
        long id = body.optLong("id", 0);
        GroupRow group = database.findGroup(id);
        if (group == null) {
            throw new ApiException(404, "群组不存在");
        }
        return reply("groups.search")
                .put("group", groupToJson(group, database.groupRole(id, selfId)))
                .put("pending", database.hasJoinRequest(id, selfId));
    }

    private JSONObject handleGroupJoinCmd(long selfId, JSONObject body) {
        long groupId = body.optLong("groupId", -1);
        GroupRow group = groupId < 0 ? null : database.findGroup(groupId);
        if (group == null) {
            throw new ApiException(404, "群组不存在");
        }
        if (database.groupRole(groupId, selfId) != null) {
            throw new ApiException(400, "你已在该群中");
        }
        JSONObject response = reply("groups.join");
        if ("open".equals(group.joinPolicy())) {
            database.addGroupMember(groupId, selfId, "member");
            response.put("joined", true);
            response.put("message", "已加入群聊");
        } else {
            database.createJoinRequest(groupId, selfId);
            response.put("joined", false);
            response.put("message", "已提交申请，等待群主/管理员审核");
        }
        return response;
    }

    private JSONObject handleGroupRequestsCmd(long selfId) {
        JSONArray array = new JSONArray();
        for (JoinRequestInfo request : database.pendingJoinRequests(selfId)) {
            array.put(request.toJson());
        }
        return reply("groups.requests").put("requests", array);
    }

    private JSONObject handleGroupRequestHandleCmd(long selfId, JSONObject body) {
        long requestId = body.optLong("requestId", -1);
        boolean approve = body.optBoolean("approve", false);
        JoinRequestInfo request = requestId < 0 ? null : database.findJoinRequest(requestId);
        if (request == null) {
            throw new ApiException(404, "申请不存在或已处理");
        }
        String role = database.groupRole(request.groupId(), selfId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw new ApiException(403, "需要群主或管理员权限");
        }
        if (approve && database.groupRole(request.groupId(), request.userId()) == null) {
            database.addGroupMember(request.groupId(), request.userId(), "member");
        }
        database.deleteJoinRequest(requestId);
        return reply("groups.handle");
    }

    private JSONObject handleGroupRenameCmd(long selfId, JSONObject body) {
        long groupId = requireManager(selfId, body);
        String name = body.optString("name", "").trim();
        if (name.isEmpty() || name.length() > 32) {
            throw new ApiException(400, "群名称需为 1-32 个字符");
        }
        database.updateGroupName(groupId, name);
        return reply("groups.rename");
    }

    private JSONObject handleGroupSettingsCmd(long selfId, JSONObject body) {
        long groupId = requireManager(selfId, body);
        GroupRow group = database.findGroup(groupId);
        String joinPolicy = body.has("joinPolicy") ? body.optString("joinPolicy") : group.joinPolicy();
        boolean mutedAll = body.has("mutedAll") ? body.optBoolean("mutedAll") : group.mutedAll();
        if (!"open".equals(joinPolicy) && !"approval".equals(joinPolicy)) {
            throw new ApiException(400, "入群方式不合法");
        }
        database.setGroupSettings(groupId, joinPolicy, mutedAll);
        return reply("groups.settings");
    }

    private JSONObject handleGroupMuteCmd(long selfId, JSONObject body) {
        long groupId = requireManager(selfId, body);
        long userId = body.optLong("userId", -1);
        boolean muted = body.optBoolean("muted", true);
        String targetRole = database.groupRole(groupId, userId);
        if (targetRole == null) {
            throw new ApiException(404, "该用户不在群中");
        }
        if ("owner".equals(targetRole)) {
            throw new ApiException(403, "不能禁言群主");
        }
        String myRole = database.groupRole(groupId, selfId);
        if ("admin".equals(targetRole) && !"owner".equals(myRole)) {
            throw new ApiException(403, "只有群主可以禁言管理员");
        }
        database.setGroupMemberMuted(groupId, userId, muted);
        return reply("groups.mute");
    }

    /** 设置成员角色（仅群主）：可将成员设为管理员、管理员降为成员，不能操作群主。 */
    private JSONObject handleGroupRoleCmd(long selfId, JSONObject body) {
        long groupId = requireManager(selfId, body);
        long userId = body.optLong("userId", -1);
        String role = body.optString("role", "");
        if (userId < 0 || (!"admin".equals(role) && !"member".equals(role))) {
            throw new ApiException(400, "角色不合法");
        }
        String targetRole = database.groupRole(groupId, userId);
        if (targetRole == null) {
            throw new ApiException(404, "该用户不在群中");
        }
        if ("owner".equals(targetRole)) {
            throw new ApiException(403, "不能修改群主的角色");
        }
        if (!"owner".equals(database.groupRole(groupId, selfId))) {
            throw new ApiException(403, "只有群主可以设置管理员");
        }
        database.setGroupMemberRole(groupId, userId, role);
        return reply("groups.role");
    }

    private JSONObject handleGroupMembersCmd(long selfId, JSONObject body) {
        long groupId = body.optLong("groupId", 0);
        if (database.groupRole(groupId, selfId) == null) {
            throw new ApiException(403, "你不是该群成员");
        }
        JSONArray array = new JSONArray();
        for (MemberInfo member : database.groupMembers(groupId)) {
            array.put(member.toJson());
        }
        return reply("groups.members").put("members", array);
    }

    // ---------------- 工具方法 ----------------

    private Long resolveBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return database.resolveToken(authorization.substring("Bearer ".length()).trim());
    }

    private static JSONObject messageEnvelope(ChatMessage message) {
        JSONObject envelope = new JSONObject();
        envelope.put("type", "message");
        envelope.put("message", message.toJson());
        return envelope;
    }

    private static JSONArray messagesToJson(List<ChatMessage> messages) {
        JSONArray array = new JSONArray();
        for (ChatMessage message : messages) {
            array.put(message.toJson());
        }
        return array;
    }

    private static void send(WebSocket connection, JSONObject payload) {
        if (connection != null && connection.isOpen()) {
            connection.send(payload.toString());
        }
    }
}
