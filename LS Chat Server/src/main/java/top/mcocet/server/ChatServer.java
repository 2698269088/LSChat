package top.mcocet.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * LS Chat 服务端 HTTP 模式：HTTPS + JSON API。
 * 所有数据（注册、登录、消息）均通过 TLS 加密传输。
 * 发送的消息同时经 {@link RealtimeHub} 实时推送给在线的 WebSocket 连接。
 */
public class ChatServer {

    private final Database database;
    private final Auth auth;
    private final RealtimeHub hub;
    private final Path dataDir;
    private HttpsServer server;

    public ChatServer(Path dataDir, Database database, Auth auth, RealtimeHub hub) {
        this.dataDir = dataDir;
        this.database = database;
        this.auth = auth;
        this.hub = hub;
    }

    public Path dataDir() {
        return dataDir;
    }

    public void start(int port, SSLContext sslContext) throws Exception {
        server = HttpsServer.create(new InetSocketAddress(port), 64);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/api/ping", this::handlePing);
        server.createContext("/api/register", this::handleRegister);
        server.createContext("/api/login", this::handleLogin);
        server.createContext("/api/users", this::handleUsers);
        server.createContext("/api/messages", this::handleMessages);
        server.createContext("/api/avatar", this::handleAvatar);
        server.createContext("/api/profile", this::handleProfile);
        server.createContext("/api/friends", this::handleFriends);
        server.createContext("/api/groups", this::handleGroups);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    /** 停止 HTTP 服务。 */
    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    // ---------------- 处理器 ----------------

    private void handlePing(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("name", "LS Chat Server");
        response.put("version", "1.0");
        response.put("time", System.currentTimeMillis());
        sendJson(exchange, 200, response);
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return;
        }
        try {
            JSONObject body = readJson(exchange);
            User user = auth.register(body.optString("username", "").trim(),
                    body.optString("password", ""));
            JSONObject response = new JSONObject();
            response.put("code", 0);
            response.put("user", user.toJson());
            sendJson(exchange, 200, response);
        } catch (ApiException e) {
            sendJson(exchange, 200, error(e.code(), e.getMessage()));
        } catch (Exception e) {
            sendJson(exchange, 200, error(500, "服务器内部错误"));
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return;
        }
        try {
            JSONObject body = readJson(exchange);
            long userId = body.optLong("userId", -1);
            String password = body.optString("password", "");
            if (userId < 0) {
                sendJson(exchange, 200, error(400, "请输入用户 ID"));
                return;
            }
            String token = auth.login(userId, password);
            long loggedInId = database.resolveToken(token);
            UserRow row = database.findUserById(loggedInId);
            JSONObject response = new JSONObject();
            response.put("code", 0);
            response.put("token", token);
            response.put("user", new User(row.id(), row.username(), "", "").toJson());
            sendJson(exchange, 200, response);
        } catch (ApiException e) {
            sendJson(exchange, 200, error(e.code(), e.getMessage()));
        } catch (Exception e) {
            sendJson(exchange, 200, error(500, "服务器内部错误"));
        }
    }

    private void handleUsers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        Long selfId = authenticate(exchange);
        if (selfId == null) {
            sendJson(exchange, 200, error(401, "未登录或登录已过期"));
            return;
        }
        // 用户列表只返回我的好友，不再暴露全部用户
        JSONArray array = new JSONArray();
        for (User user : database.friendsOf(selfId)) {
            array.put(user.toJson());
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("users", array);
        sendJson(exchange, 200, response);
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        Long selfId = authenticate(exchange);
        if (selfId == null) {
            sendJson(exchange, 200, error(401, "未登录或登录已过期"));
            return;
        }
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            Map<String, String> query = parseQuery(exchange);
            long after = Long.parseLong(query.getOrDefault("after", "0"));
            List<ChatMessage> list;
            if (query.containsKey("group")) {
                // 群聊历史（需是群成员）
                long groupId = Long.parseLong(query.get("group"));
                if (database.groupRole(groupId, selfId) == null) {
                    sendJson(exchange, 200, error(403, "你不是该群成员"));
                    return;
                }
                list = database.groupMessages(groupId, after, 200);
            } else if (query.containsKey("peer")) {
                long peer = Long.parseLong(query.get("peer"));
                list = database.messagesBetween(selfId, peer, after, 200);
            } else {
                // 无 peer/group 参数：拉取发给我的私聊 + 我所在群的新消息（后台轮询）
                List<Long> myGroups = new java.util.ArrayList<>();
                for (GroupRow group : database.groupsOf(selfId)) {
                    myGroups.add(group.id());
                }
                list = database.incomingMessages(selfId, myGroups, after, 200);
            }
            JSONArray array = new JSONArray();
            for (ChatMessage message : list) {
                array.put(message.toJson());
            }
            JSONObject response = new JSONObject();
            response.put("code", 0);
            response.put("messages", array);
            sendJson(exchange, 200, response);
        } else if ("POST".equalsIgnoreCase(method)) {
            try {
                JSONObject body = readJson(exchange);
                if (body.has("group")) {
                    handleGroupSend(exchange, selfId, body);
                    return;
                }
                long peer = body.optLong("peer", -1);
                String type = body.optString("type", "");
                String content = body.optString("content", "");
                if (peer < 0 || database.findUserById(peer) == null) {
                    sendJson(exchange, 200, error(400, "接收者不存在"));
                    return;
                }
                if (!validateContent(exchange, type, content)) return;
                ChatMessage message = database.addMessage(selfId, peer, 0, type, content);
                JSONObject response = new JSONObject();
                response.put("code", 0);
                response.put("message", message.toJson());
                sendJson(exchange, 200, response);
                // 实时推送给接收者的 WebSocket 连接
                if (peer != selfId) {
                    JSONObject envelope = new JSONObject();
                    envelope.put("type", "message");
                    envelope.put("message", message.toJson());
                    hub.pushToUser(peer, envelope);
                }
            } catch (Exception e) {
                sendJson(exchange, 200, error(500, "服务器内部错误"));
            }
        } else {
            sendJson(exchange, 405, error(405, "仅支持 GET/POST"));
        }
    }

    // ---------------- 头像 ----------------

    /** GET /api/avatar?id=N 取头像；POST /api/avatar 设置自己的头像（JPEG/PNG Base64）。 */
    private void handleAvatar(HttpExchange exchange) throws IOException {
        Long selfId = authenticate(exchange);
        if (selfId == null) {
            sendJson(exchange, 200, error(401, "未登录或登录已过期"));
            return;
        }
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            Map<String, String> query = parseQuery(exchange);
            long id;
            try {
                id = Long.parseLong(query.getOrDefault("id", "0"));
            } catch (NumberFormatException e) {
                sendJson(exchange, 200, error(400, "用户 ID 无效"));
                return;
            }
            JSONObject response = new JSONObject();
            response.put("code", 0);
            response.put("avatar", database.avatarOf(id));
            sendJson(exchange, 200, response);
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            sendJson(exchange, 405, error(405, "仅支持 GET/POST"));
            return;
        }
        try {
            JSONObject body = readJson(exchange);
            String content = body.optString("content", "");
            String invalid = MessageContent.validateAvatar(content);
            if (invalid != null) {
                sendJson(exchange, 200, error(400, invalid));
                return;
            }
            database.setAvatar(selfId, content);
            JSONObject response = new JSONObject();
            response.put("code", 0);
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            sendJson(exchange, 200, error(500, "服务器内部错误"));
        }
    }

    // ---------------- 个人资料 ----------------

    /** GET /api/profile 取自己的签名与状态；POST 设置（签名≤30字，状态≤20字）。 */
    private void handleProfile(HttpExchange exchange) throws IOException {
        Long selfId = authenticate(exchange);
        if (selfId == null) {
            sendJson(exchange, 200, error(401, "未登录或登录已过期"));
            return;
        }
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            JSONObject response = new JSONObject();
            response.put("code", 0);
            response.put("profile", database.profileOf(selfId).toJson());
            sendJson(exchange, 200, response);
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            sendJson(exchange, 405, error(405, "仅支持 GET/POST"));
            return;
        }
        try {
            JSONObject body = readJson(exchange);
            String signature = body.optString("signature", "").trim();
            String status = body.optString("status", "").trim();
            if (signature.length() > MessageContent.MAX_SIGNATURE_LENGTH) {
                sendJson(exchange, 200, error(400, "签名最多 " + MessageContent.MAX_SIGNATURE_LENGTH + " 字"));
                return;
            }
            if (status.length() > MessageContent.MAX_STATUS_LENGTH) {
                sendJson(exchange, 200, error(400, "状态最多 " + MessageContent.MAX_STATUS_LENGTH + " 字"));
                return;
            }
            database.setProfile(selfId, signature, status);
            JSONObject response = new JSONObject();
            response.put("code", 0);
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            sendJson(exchange, 200, error(500, "服务器内部错误"));
        }
    }

    // ---------------- 好友 ----------------

    /** 好友接口分发：/api/friends/lookup、/api/friends/request、/api/friends/requests ... */
    private void handleFriends(HttpExchange exchange) throws IOException {
        Long selfId = authenticate(exchange);
        if (selfId == null) {
            sendJson(exchange, 200, error(401, "未登录或登录已过期"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String sub = path.length() > "/api/friends".length() ? path.substring("/api/friends".length()) : "";
        String method = exchange.getRequestMethod();
        try {
            switch (sub) {
                case "/lookup" -> handleFriendLookup(exchange, selfId);
                case "/request" -> handleFriendRequestSend(exchange, selfId);
                case "/requests" -> {
                    if ("GET".equalsIgnoreCase(method)) handleFriendRequestList(exchange, selfId);
                    else handleFriendRequestHandle(exchange, selfId);
                }
                default -> sendJson(exchange, 404, error(404, "接口不存在"));
            }
        } catch (Exception e) {
            sendJson(exchange, 200, error(500, "服务器内部错误"));
        }
    }

    /** 按用户 ID 查找（添加好友前确认对方）。 */
    private void handleFriendLookup(HttpExchange exchange, long selfId) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        Map<String, String> query = parseQuery(exchange);
        long id;
        try {
            id = Long.parseLong(query.getOrDefault("id", "0"));
        } catch (NumberFormatException e) {
            sendJson(exchange, 200, error(400, "用户 ID 无效"));
            return;
        }
        UserRow user = id <= 0 ? null : database.findUserById(id);
        if (user == null) {
            sendJson(exchange, 200, error(404, "用户不存在"));
            return;
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("user", new User(user.id(), user.username(), "", "").toJson());
        sendJson(exchange, 200, response);
    }

    /** 发送好友申请：对方已向我申请过则直接互为好友。 */
    private void handleFriendRequestSend(HttpExchange exchange, long selfId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return;
        }
        JSONObject body = readJson(exchange);
        long userId = body.optLong("userId", -1);
        UserRow target = userId < 0 ? null : database.findUserById(userId);
        if (target == null) {
            sendJson(exchange, 200, error(404, "用户不存在"));
            return;
        }
        if (userId == selfId) {
            sendJson(exchange, 200, error(400, "不能添加自己为好友"));
            return;
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        if (database.areFriends(selfId, userId)) {
            sendJson(exchange, 200, error(400, "对方已经是你的好友"));
            return;
        }
        if (database.hasFriendRequest(selfId, userId)) {
            sendJson(exchange, 200, error(400, "已发送过申请，等待对方同意"));
            return;
        }
        if (database.hasFriendRequest(userId, selfId)) {
            // 对方已向我发过申请：直接互为好友，并清理双方的申请记录
            database.addFriendship(selfId, userId);
            FriendRequestInfo pending = database.pendingFriendRequests(selfId).stream()
                    .filter(r -> r.userId() == userId).findFirst().orElse(null);
            if (pending != null) database.deleteFriendRequest(pending.id());
            response.put("message", "对方已向你发送过申请，现已互为好友");
            sendJson(exchange, 200, response);
            return;
        }
        database.createFriendRequest(selfId, userId);
        response.put("message", "已发送好友申请，等待对方同意");
        sendJson(exchange, 200, response);
    }

    private void handleFriendRequestList(HttpExchange exchange, long selfId) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        JSONArray array = new JSONArray();
        for (FriendRequestInfo request : database.pendingFriendRequests(selfId)) {
            array.put(request.toJson());
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("requests", array);
        sendJson(exchange, 200, response);
    }

    /** 处理发给我的好友申请：同意建立双向好友关系，拒绝则删除申请。 */
    private void handleFriendRequestHandle(HttpExchange exchange, long selfId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return;
        }
        JSONObject body = readJson(exchange);
        long requestId = body.optLong("requestId", -1);
        boolean approve = body.optBoolean("approve", false);
        FriendRequestInfo request = requestId < 0 ? null : database.findFriendRequest(requestId);
        if (request == null) {
            sendJson(exchange, 200, error(404, "申请不存在或已处理"));
            return;
        }
        // 只有申请接收者本人可以处理
        if (!database.friendRequestBelongsTo(requestId, selfId)) {
            sendJson(exchange, 200, error(403, "无权处理该申请"));
            return;
        }
        if (approve) {
            database.addFriendship(selfId, request.userId());
        }
        database.deleteFriendRequest(requestId);
        JSONObject response = new JSONObject();
        response.put("code", 0);
        sendJson(exchange, 200, response);
    }

    // ---------------- 群组 ----------------

    /** 群消息发送：校验成员身份与禁言状态。 */
    private void handleGroupSend(HttpExchange exchange, long selfId, JSONObject body) throws IOException {
        long groupId = body.optLong("group", -1);
        String type = body.optString("type", "");
        String content = body.optString("content", "");
        GroupRow group = groupId < 0 ? null : database.findGroup(groupId);
        if (group == null) {
            sendJson(exchange, 200, error(404, "群组不存在"));
            return;
        }
        String role = database.groupRole(groupId, selfId);
        if (role == null) {
            sendJson(exchange, 200, error(403, "你不是该群成员"));
            return;
        }
        if (group.mutedAll() && "member".equals(role)) {
            sendJson(exchange, 200, error(403, "群主已开启全员禁言"));
            return;
        }
        if (database.groupMemberMuted(groupId, selfId)) {
            sendJson(exchange, 200, error(403, "你已被管理员禁言"));
            return;
        }
        if (!validateContent(exchange, type, content)) return;
        ChatMessage message = database.addMessage(selfId, 0, groupId, type, content);
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("message", message.toJson());
        sendJson(exchange, 200, response);
        // 实时推送给群内其他成员
        JSONObject envelope = new JSONObject();
        envelope.put("type", "message");
        envelope.put("message", message.toJson());
        hub.pushToGroup(groupId, selfId, envelope);
    }

    /** 群接口分发：/api/groups、/api/groups/create ... */
    private void handleGroups(HttpExchange exchange) throws IOException {
        Long selfId = authenticate(exchange);
        if (selfId == null) {
            sendJson(exchange, 200, error(401, "未登录或登录已过期"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String sub = path.length() > "/api/groups".length() ? path.substring("/api/groups".length()) : "";
        String method = exchange.getRequestMethod();
        try {
            switch (sub) {
                case "" -> handleGroupList(exchange, selfId);
                case "/create" -> handleGroupCreate(exchange, selfId);
                case "/search" -> handleGroupSearch(exchange, selfId);
                case "/join" -> handleGroupJoin(exchange, selfId);
                case "/requests" -> {
                    if ("GET".equalsIgnoreCase(method)) handleGroupRequests(exchange, selfId);
                    else handleGroupRequestHandle(exchange, selfId);
                }
                case "/rename" -> handleGroupRename(exchange, selfId);
                case "/settings" -> handleGroupSettings(exchange, selfId);
                case "/mute" -> handleGroupMute(exchange, selfId);
                case "/role" -> handleGroupRole(exchange, selfId);
                case "/members" -> handleGroupMembers(exchange, selfId);
                default -> sendJson(exchange, 404, error(404, "接口不存在"));
            }
        } catch (Exception e) {
            sendJson(exchange, 200, error(500, "服务器内部错误"));
        }
    }

    private void handleGroupList(HttpExchange exchange, long selfId) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        JSONArray array = new JSONArray();
        for (GroupRow group : database.groupsOf(selfId)) {
            JSONObject object = groupToJson(group, database.groupRole(group.id(), selfId));
            array.put(object);
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("groups", array);
        sendJson(exchange, 200, response);
    }

    private void handleGroupCreate(HttpExchange exchange, long selfId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return;
        }
        JSONObject body = readJson(exchange);
        String name = body.optString("name", "").trim();
        String joinPolicy = body.optString("joinPolicy", "approval");
        if (name.isEmpty() || name.length() > 32) {
            sendJson(exchange, 200, error(400, "群名称需为 1-32 个字符"));
            return;
        }
        if (!"open".equals(joinPolicy) && !"approval".equals(joinPolicy)) {
            sendJson(exchange, 200, error(400, "入群方式不合法"));
            return;
        }
        long groupId = database.createGroup(name, selfId, joinPolicy);
        database.addGroupMember(groupId, selfId, "owner");
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("group", groupToJson(database.findGroup(groupId), "owner"));
        sendJson(exchange, 200, response);
    }

    private void handleGroupSearch(HttpExchange exchange, long selfId) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        Map<String, String> query = parseQuery(exchange);
        long id;
        try {
            id = Long.parseLong(query.getOrDefault("id", "0"));
        } catch (NumberFormatException e) {
            sendJson(exchange, 200, error(400, "群 ID 无效"));
            return;
        }
        GroupRow group = database.findGroup(id);
        if (group == null) {
            sendJson(exchange, 200, error(404, "群组不存在"));
            return;
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("group", groupToJson(group, database.groupRole(id, selfId)));
        response.put("pending", database.hasJoinRequest(id, selfId));
        sendJson(exchange, 200, response);
    }

    private void handleGroupJoin(HttpExchange exchange, long selfId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return;
        }
        JSONObject body = readJson(exchange);
        long groupId = body.optLong("groupId", -1);
        GroupRow group = groupId < 0 ? null : database.findGroup(groupId);
        if (group == null) {
            sendJson(exchange, 200, error(404, "群组不存在"));
            return;
        }
        if (database.groupRole(groupId, selfId) != null) {
            sendJson(exchange, 200, error(400, "你已在该群中"));
            return;
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        if ("open".equals(group.joinPolicy())) {
            database.addGroupMember(groupId, selfId, "member");
            response.put("joined", true);
            response.put("message", "已加入群聊");
        } else {
            database.createJoinRequest(groupId, selfId);
            response.put("joined", false);
            response.put("message", "已提交申请，等待群主/管理员审核");
        }
        sendJson(exchange, 200, response);
    }

    private void handleGroupRequests(HttpExchange exchange, long selfId) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        JSONArray array = new JSONArray();
        for (JoinRequestInfo request : database.pendingJoinRequests(selfId)) {
            array.put(request.toJson());
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("requests", array);
        sendJson(exchange, 200, response);
    }

    private void handleGroupRequestHandle(HttpExchange exchange, long selfId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return;
        }
        JSONObject body = readJson(exchange);
        long requestId = body.optLong("requestId", -1);
        boolean approve = body.optBoolean("approve", false);
        JoinRequestInfo request = requestId < 0 ? null : database.findJoinRequest(requestId);
        if (request == null) {
            sendJson(exchange, 200, error(404, "申请不存在或已处理"));
            return;
        }
        String role = database.groupRole(request.groupId(), selfId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            sendJson(exchange, 200, error(403, "需要群主或管理员权限"));
            return;
        }
        if (approve && database.groupRole(request.groupId(), request.userId()) == null) {
            database.addGroupMember(request.groupId(), request.userId(), "member");
        }
        database.deleteJoinRequest(requestId);
        JSONObject response = new JSONObject();
        response.put("code", 0);
        sendJson(exchange, 200, response);
    }

    private void handleGroupRename(HttpExchange exchange, long selfId) throws IOException {
        if (!requireManager(exchange, selfId)) return;
        JSONObject body = (JSONObject) exchange.getAttribute("body");
        long groupId = (Long) exchange.getAttribute("groupId");
        String name = body.optString("name", "").trim();
        if (name.isEmpty() || name.length() > 32) {
            sendJson(exchange, 200, error(400, "群名称需为 1-32 个字符"));
            return;
        }
        database.updateGroupName(groupId, name);
        JSONObject response = new JSONObject();
        response.put("code", 0);
        sendJson(exchange, 200, response);
    }

    private void handleGroupSettings(HttpExchange exchange, long selfId) throws IOException {
        if (!requireManager(exchange, selfId)) return;
        JSONObject body = (JSONObject) exchange.getAttribute("body");
        long groupId = (Long) exchange.getAttribute("groupId");
        GroupRow group = database.findGroup(groupId);
        if (group == null) {
            sendJson(exchange, 200, error(404, "群组不存在"));
            return;
        }
        String joinPolicy = body.has("joinPolicy") ? body.optString("joinPolicy") : group.joinPolicy();
        boolean mutedAll = body.has("mutedAll") ? body.optBoolean("mutedAll") : group.mutedAll();
        if (!"open".equals(joinPolicy) && !"approval".equals(joinPolicy)) {
            sendJson(exchange, 200, error(400, "入群方式不合法"));
            return;
        }
        database.setGroupSettings(groupId, joinPolicy, mutedAll);
        JSONObject response = new JSONObject();
        response.put("code", 0);
        sendJson(exchange, 200, response);
    }

    private void handleGroupMute(HttpExchange exchange, long selfId) throws IOException {
        if (!requireManager(exchange, selfId)) return;
        JSONObject body = (JSONObject) exchange.getAttribute("body");
        long groupId = (Long) exchange.getAttribute("groupId");
        long userId = body.optLong("userId", -1);
        boolean muted = body.optBoolean("muted", true);
        String targetRole = database.groupRole(groupId, userId);
        if (targetRole == null) {
            sendJson(exchange, 200, error(404, "该用户不在群中"));
            return;
        }
        if ("owner".equals(targetRole)) {
            sendJson(exchange, 200, error(403, "不能禁言群主"));
            return;
        }
        String myRole = database.groupRole(groupId, selfId);
        if ("admin".equals(targetRole) && !"owner".equals(myRole)) {
            sendJson(exchange, 200, error(403, "只有群主可以禁言管理员"));
            return;
        }
        database.setGroupMemberMuted(groupId, userId, muted);
        JSONObject response = new JSONObject();
        response.put("code", 0);
        sendJson(exchange, 200, response);
    }

    /** 设置成员角色（仅群主）：可将成员设为管理员、管理员降为成员，不能操作群主。 */
    private void handleGroupRole(HttpExchange exchange, long selfId) throws IOException {
        if (!requireManager(exchange, selfId)) return;
        JSONObject body = (JSONObject) exchange.getAttribute("body");
        long groupId = (Long) exchange.getAttribute("groupId");
        long userId = body.optLong("userId", -1);
        String role = body.optString("role", "");
        if (userId < 0 || (!"admin".equals(role) && !"member".equals(role))) {
            sendJson(exchange, 200, error(400, "角色不合法"));
            return;
        }
        String targetRole = database.groupRole(groupId, userId);
        if (targetRole == null) {
            sendJson(exchange, 200, error(404, "该用户不在群中"));
            return;
        }
        if ("owner".equals(targetRole)) {
            sendJson(exchange, 200, error(403, "不能修改群主的角色"));
            return;
        }
        if (!"owner".equals(database.groupRole(groupId, selfId))) {
            sendJson(exchange, 200, error(403, "只有群主可以设置管理员"));
            return;
        }
        database.setGroupMemberRole(groupId, userId, role);
        JSONObject response = new JSONObject();
        response.put("code", 0);
        sendJson(exchange, 200, response);
    }

    private void handleGroupMembers(HttpExchange exchange, long selfId) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 GET"));
            return;
        }
        Map<String, String> query = parseQuery(exchange);
        long groupId = Long.parseLong(query.getOrDefault("groupId", "0"));
        if (database.groupRole(groupId, selfId) == null) {
            sendJson(exchange, 200, error(403, "你不是该群成员"));
            return;
        }
        JSONArray array = new JSONArray();
        for (MemberInfo member : database.groupMembers(groupId)) {
            array.put(member.toJson());
        }
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("members", array);
        sendJson(exchange, 200, response);
    }

    /** 校验群主/管理员权限并取出 groupId；无权限时直接响应并返回 false。 */
    private boolean requireManager(HttpExchange exchange, long selfId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error(405, "仅支持 POST"));
            return false;
        }
        JSONObject body = readJson(exchange);
        long groupId = body.optLong("groupId", -1);
        GroupRow group = groupId < 0 ? null : database.findGroup(groupId);
        if (group == null) {
            sendJson(exchange, 200, error(404, "群组不存在"));
            return false;
        }
        String role = database.groupRole(groupId, selfId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            sendJson(exchange, 200, error(403, "需要群主或管理员权限"));
            return false;
        }
        exchange.setAttribute("groupId", groupId);
        exchange.setAttribute("body", body);
        return true;
    }

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

    /** 校验消息类型与内容长度，不合法时直接响应并返回 false。 */
    private boolean validateContent(HttpExchange exchange, String type, String content) throws IOException {
        String invalid = MessageContent.validate(type, content);
        if (invalid != null) {
            sendJson(exchange, 200, error(400, invalid));
            return false;
        }
        return true;
    }

    // ---------------- 工具方法 ----------------

    private Long authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return database.resolveToken(header.substring("Bearer ".length()).trim());
    }

    private static JSONObject readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private static void sendJson(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static JSONObject error(int code, String message) {
        JSONObject object = new JSONObject();
        object.put("code", code);
        object.put("message", message);
        return object;
    }

    private static Map<String, String> parseQuery(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return result;
        }
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                String key = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
                result.put(key, value);
            }
        }
        return result;
    }
}
