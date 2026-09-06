package top.mcocet.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 持久化接口。默认使用 SQLite（内嵌，零配置），
 * 数据库类型与连接信息由配置文件（server.properties 的 db.* 项）指定，
 * 未配置时回退到环境变量：
 * <ul>
 *   <li>LSCHAT_DB=mysql</li>
 *   <li>LSCHAT_DB_URL=jdbc:mysql://主机:3306/数据库名</li>
 *   <li>LSCHAT_DB_USER（默认 root）</li>
 *   <li>LSCHAT_DB_PASS（默认空）</li>
 * </ul>
 */
public interface Database extends AutoCloseable {

    static Database create(Path dataDir, ServerConfig config) throws Exception {
        String type = config.dbType() != null
                ? config.dbType()
                : System.getenv().getOrDefault("LSCHAT_DB", "sqlite");
        type = type.toLowerCase(Locale.ROOT);
        if ("mysql".equals(type)) {
            String url = config.dbUrl() != null ? config.dbUrl() : System.getenv("LSCHAT_DB_URL");
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("使用 MySQL 需在配置文件中设置 db.url，或设置 LSCHAT_DB_URL，例如 jdbc:mysql://localhost:3306/lschat");
            }
            String user = config.dbUser() != null
                    ? config.dbUser()
                    : System.getenv().getOrDefault("LSCHAT_DB_USER", "root");
            String pass = config.dbPass() != null
                    ? config.dbPass()
                    : System.getenv().getOrDefault("LSCHAT_DB_PASS", "");
            return new MySqlDatabase(url, user, pass);
        }
        Files.createDirectories(dataDir);
        return new SqliteDatabase(dataDir);
    }

    /** 存储后端描述，用于启动日志。 */
    String describe();

    UserRow createUser(String username, String saltBase64, String passwordHash);

    UserRow findUserByName(String username);

    UserRow findUserById(long id);

    /** 设置用户头像（Base64，空串表示清除）。 */
    void setAvatar(long userId, String avatarBase64);

    /** 用户头像（Base64），未设置返回空串。 */
    String avatarOf(long userId);

    /** 设置个人资料（签名与状态）。 */
    void setProfile(long userId, String signature, String status);

    /** 个人资料，未设置时返回空串。 */
    UserProfile profileOf(long userId);

    /** 我的好友列表（双向）。 */
    List<User> friendsOf(long userId);

    boolean areFriends(long a, long b);

    /** 建立双向好友关系。 */
    void addFriendship(long a, long b);

    // ---------------- 好友申请 ----------------

    /** 创建好友申请，重复申请返回 0。 */
    long createFriendRequest(long fromId, long toId);

    boolean hasFriendRequest(long fromId, long toId);

    /** 发给我的待处理好友申请。 */
    List<FriendRequestInfo> pendingFriendRequests(long userId);

    FriendRequestInfo findFriendRequest(long id);

    /** 指定申请是否是发给该用户的。 */
    boolean friendRequestBelongsTo(long requestId, long userId);

    void deleteFriendRequest(long id);

    /** 发送消息：私聊 to>0、groupId=0；群聊 to=0、groupId>0。 */
    ChatMessage addMessage(long from, long to, long groupId, String type, String content);

    /** a 与 b 之间的私聊消息，id 大于 after，按 id 升序，最多 limit 条。 */
    List<ChatMessage> messagesBetween(long a, long b, long after, int limit);

    /** 发给指定用户的所有私聊消息（后台轮询用）。 */
    List<ChatMessage> messagesTo(long recipient, long after, int limit);

    /** 指定群的消息，id 大于 after，按 id 升序，最多 limit 条。 */
    List<ChatMessage> groupMessages(long groupId, long after, int limit);

    /** 发给我的私聊 + 我所在群的消息（后台轮询用）。 */
    List<ChatMessage> incomingMessages(long userId, List<Long> groupIds, long after, int limit);

    // ---------------- 群组 ----------------

    /** 创建群，返回群 ID。joinPolicy: approval(需审核) / open(直接加入)。 */
    long createGroup(String name, long ownerId, String joinPolicy);

    GroupRow findGroup(long id);

    void updateGroupName(long id, String name);

    /** 设置入群方式与全员禁言。 */
    void setGroupSettings(long id, String joinPolicy, boolean mutedAll);

    void addGroupMember(long groupId, long userId, String role);

    /** 修改成员角色（owner/admin/member）。 */
    void setGroupMemberRole(long groupId, long userId, String role);

    /** 返回成员角色（owner/admin/member），非成员返回 null。 */
    String groupRole(long groupId, long userId);

    boolean groupMemberMuted(long groupId, long userId);

    void setGroupMemberMuted(long groupId, long userId, boolean muted);

    int groupMemberCount(long groupId);

    /** 我加入的所有群。 */
    List<GroupRow> groupsOf(long userId);

    List<MemberInfo> groupMembers(long groupId);

    /** 创建入群申请，重复申请返回 0。 */
    long createJoinRequest(long groupId, long userId);

    boolean hasJoinRequest(long groupId, long userId);

    /** 我管理的群（群主/管理员）的待处理入群申请。 */
    List<JoinRequestInfo> pendingJoinRequests(long managerId);

    JoinRequestInfo findJoinRequest(long id);

    void deleteJoinRequest(long id);

    void saveToken(String token, long userId);

    Long resolveToken(String token);

    void removeToken(String token);

    @Override
    default void close() {
    }
}
