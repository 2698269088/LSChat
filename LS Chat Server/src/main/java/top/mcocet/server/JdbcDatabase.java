package top.mcocet.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JDBC 的存储基类，SQLite 与 MySQL 共用实现，
 * 子类提供建表语句、令牌 upsert 语法与迁移逻辑。方法串行化（单连接加锁），
 * 适用于中小规模场景。
 */
public abstract class JdbcDatabase implements Database {

    protected final Connection connection;

    protected JdbcDatabase(String jdbcUrl, String user, String password) throws Exception {
        this.connection = DriverManager.getConnection(jdbcUrl, user, password);
        try (Statement statement = connection.createStatement()) {
            for (String ddl : ddlStatements()) {
                statement.execute(ddl);
            }
        }
        runMigrations();
    }

    /** 建表语句（IF NOT EXISTS）。 */
    protected abstract List<String> ddlStatements();

    /** 令牌写入语句（存在则覆盖）。 */
    protected abstract String upsertTokenSql();

    /** 好友关系写入语句（存在则忽略，需写入双向两条）。 */
    protected abstract String insertFriendSql();

    /** 旧库结构升级（如 messages 表补充 group_id 列）。 */
    protected abstract void runMigrations() throws SQLException;

    // ---------------- 用户 ----------------

    @Override
    public synchronized UserRow createUser(String username, String saltBase64, String passwordHash) {
        String sql = "INSERT INTO users (username, salt, pass) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, saltBase64);
            ps.setString(3, passwordHash);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new UserRow(keys.getLong(1), username, saltBase64, passwordHash);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建用户失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized UserRow findUserByName(String username) {
        String sql = "SELECT id, username, salt, pass FROM users WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapUser(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized UserRow findUserById(long id) {
        String sql = "SELECT id, username, salt, pass FROM users WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapUser(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户失败: " + e.getMessage(), e);
        }
    }

    private static UserRow mapUser(ResultSet rs) throws SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("username"),
                rs.getString("salt"), rs.getString("pass"));
    }

    @Override
    public synchronized void setAvatar(long userId, String avatarBase64) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE users SET avatar = ? WHERE id = ?")) {
            ps.setString(1, avatarBase64);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存头像失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized String avatarOf(long userId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT avatar FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("avatar") : "";
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询头像失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void setProfile(long userId, String signature, String status) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET signature = ?, status = ? WHERE id = ?")) {
            ps.setString(1, signature);
            ps.setString(2, status);
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存个人资料失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized UserProfile profileOf(long userId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT signature, status FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new UserProfile("", "");
                return new UserProfile(rs.getString("signature"), rs.getString("status"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询个人资料失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<User> friendsOf(long userId) {
        List<User> result = new ArrayList<>();
        String sql = "SELECT u.id, u.username, u.signature, u.status FROM friends f " +
                "JOIN users u ON u.id = f.friend_id WHERE f.user_id = ? ORDER BY u.id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new User(rs.getLong("id"), rs.getString("username"),
                            rs.getString("signature"), rs.getString("status")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询好友列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean areFriends(long a, long b) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM friends WHERE user_id = ? AND friend_id = ?")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询好友关系失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void addFriendship(long a, long b) {
        try (PreparedStatement ps = connection.prepareStatement(insertFriendSql())) {
            long time = System.currentTimeMillis();
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.setLong(3, time);
            ps.executeUpdate();
            ps.setLong(1, b);
            ps.setLong(2, a);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("建立好友关系失败: " + e.getMessage(), e);
        }
    }

    // ---------------- 好友申请 ----------------

    @Override
    public synchronized long createFriendRequest(long fromId, long toId) {
        if (hasFriendRequest(fromId, toId)) return 0;
        String sql = "INSERT INTO friend_requests (from_id, to_id, created_ms) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fromId);
            ps.setLong(2, toId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            // 并发重复申请等情况下视为已申请
            return 0;
        }
    }

    @Override
    public synchronized boolean hasFriendRequest(long fromId, long toId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM friend_requests WHERE from_id = ? AND to_id = ?")) {
            ps.setLong(1, fromId);
            ps.setLong(2, toId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询好友申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<FriendRequestInfo> pendingFriendRequests(long userId) {
        List<FriendRequestInfo> result = new ArrayList<>();
        String sql = "SELECT r.id, r.from_id, u.username FROM friend_requests r " +
                "JOIN users u ON u.id = r.from_id WHERE r.to_id = ? ORDER BY r.id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new FriendRequestInfo(rs.getLong("id"),
                            rs.getLong("from_id"), rs.getString("username")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询好友申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized FriendRequestInfo findFriendRequest(long id) {
        String sql = "SELECT r.id, r.from_id, u.username FROM friend_requests r " +
                "JOIN users u ON u.id = r.from_id WHERE r.id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new FriendRequestInfo(rs.getLong("id"),
                        rs.getLong("from_id"), rs.getString("username"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询好友申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean friendRequestBelongsTo(long requestId, long userId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM friend_requests WHERE id = ? AND to_id = ?")) {
            ps.setLong(1, requestId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询好友申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void deleteFriendRequest(long id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM friend_requests WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除好友申请失败: " + e.getMessage(), e);
        }
    }

    // ---------------- 消息 ----------------

    private static final String MESSAGE_COLUMNS = "id, sender, recipient, group_id, type, content, time_ms";

    private static ChatMessage mapMessage(ResultSet rs) throws SQLException {
        return new ChatMessage(rs.getLong("id"), rs.getLong("sender"), rs.getLong("recipient"),
                rs.getLong("group_id"), rs.getString("type"),
                rs.getString("content"), rs.getLong("time_ms"));
    }

    @Override
    public synchronized ChatMessage addMessage(long from, long to, long groupId, String type, String content) {
        long time = System.currentTimeMillis();
        String sql = "INSERT INTO messages (sender, recipient, group_id, type, content, time_ms) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setLong(3, groupId);
            ps.setString(4, type);
            ps.setString(5, content);
            ps.setLong(6, time);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new ChatMessage(keys.getLong(1), from, to, groupId, type, content, time);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存消息失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<ChatMessage> messagesBetween(long a, long b, long after, int limit) {
        List<ChatMessage> result = new ArrayList<>();
        String sql = "SELECT " + MESSAGE_COLUMNS + " FROM messages " +
                "WHERE ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)) " +
                "AND group_id = 0 AND id > ? ORDER BY id ASC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.setLong(3, b);
            ps.setLong(4, a);
            ps.setLong(5, after);
            ps.setInt(6, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMessage(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<ChatMessage> messagesTo(long recipient, long after, int limit) {
        List<ChatMessage> result = new ArrayList<>();
        String sql = "SELECT " + MESSAGE_COLUMNS + " FROM messages " +
                "WHERE recipient = ? AND id > ? ORDER BY id ASC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, recipient);
            ps.setLong(2, after);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMessage(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<ChatMessage> groupMessages(long groupId, long after, int limit) {
        List<ChatMessage> result = new ArrayList<>();
        String sql = "SELECT " + MESSAGE_COLUMNS + " FROM messages " +
                "WHERE group_id = ? AND id > ? ORDER BY id ASC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, after);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMessage(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询群消息失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<ChatMessage> incomingMessages(long userId, List<Long> groupIds, long after, int limit) {
        List<ChatMessage> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + MESSAGE_COLUMNS + " FROM messages " +
                "WHERE id > ? AND sender <> ? AND (recipient = ?");
        for (int i = 0; i < groupIds.size(); i++) {
            sql.append(" OR group_id = ?");
        }
        sql.append(") ORDER BY id ASC LIMIT ?");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setLong(index++, after);
            ps.setLong(index++, userId);
            ps.setLong(index++, userId);
            for (Long groupId : groupIds) {
                ps.setLong(index++, groupId);
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMessage(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息失败: " + e.getMessage(), e);
        }
    }

    // ---------------- 群组 ----------------

    private static GroupRow mapGroup(ResultSet rs) throws SQLException {
        return new GroupRow(rs.getLong("id"), rs.getString("name"), rs.getLong("owner_id"),
                rs.getString("join_policy"), rs.getInt("muted_all") != 0);
    }

    @Override
    public synchronized long createGroup(String name, long ownerId, String joinPolicy) {
        String sql = "INSERT INTO groups (name, owner_id, join_policy, muted_all) VALUES (?, ?, ?, 0)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setLong(2, ownerId);
            ps.setString(3, joinPolicy);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建群失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized GroupRow findGroup(long id) {
        String sql = "SELECT id, name, owner_id, join_policy, muted_all FROM groups WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapGroup(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void updateGroupName(long id, String name) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE groups SET name = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("修改群名称失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void setGroupSettings(long id, String joinPolicy, boolean mutedAll) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE groups SET join_policy = ?, muted_all = ? WHERE id = ?")) {
            ps.setString(1, joinPolicy);
            ps.setInt(2, mutedAll ? 1 : 0);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("修改群设置失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void addGroupMember(long groupId, long userId, String role) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO group_members (group_id, user_id, role, muted) VALUES (?, ?, ?, 0)")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            ps.setString(3, role);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("添加群成员失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void setGroupMemberRole(long groupId, long userId, String role) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE group_members SET role = ? WHERE group_id = ? AND user_id = ?")) {
            ps.setString(1, role);
            ps.setLong(2, groupId);
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("设置成员角色失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized String groupRole(long groupId, long userId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT role FROM group_members WHERE group_id = ? AND user_id = ?")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("role") : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群角色失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean groupMemberMuted(long groupId, long userId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT muted FROM group_members WHERE group_id = ? AND user_id = ?")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("muted") != 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询禁言状态失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void setGroupMemberMuted(long groupId, long userId, boolean muted) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE group_members SET muted = ? WHERE group_id = ? AND user_id = ?")) {
            ps.setInt(1, muted ? 1 : 0);
            ps.setLong(2, groupId);
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("设置禁言失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized int groupMemberCount(long groupId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM group_members WHERE group_id = ?")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群人数失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<GroupRow> groupsOf(long userId) {
        List<GroupRow> result = new ArrayList<>();
        String sql = "SELECT g.id, g.name, g.owner_id, g.join_policy, g.muted_all " +
                "FROM groups g JOIN group_members m ON m.group_id = g.id " +
                "WHERE m.user_id = ? ORDER BY g.id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapGroup(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询我的群失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<MemberInfo> groupMembers(long groupId) {
        List<MemberInfo> result = new ArrayList<>();
        String sql = "SELECT m.user_id, u.username, m.role, m.muted FROM group_members m " +
                "JOIN users u ON u.id = m.user_id WHERE m.group_id = ? ORDER BY m.rowid";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MemberInfo(rs.getLong("user_id"), rs.getString("username"),
                            rs.getString("role"), rs.getInt("muted") != 0));
                }
            }
            return result;
        } catch (SQLException e) {
            // MySQL 无 rowid，退回按 user_id 排序
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT m.user_id, u.username, m.role, m.muted FROM group_members m " +
                            "JOIN users u ON u.id = m.user_id WHERE m.group_id = ? ORDER BY m.user_id")) {
                ps.setLong(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new MemberInfo(rs.getLong("user_id"), rs.getString("username"),
                                rs.getString("role"), rs.getInt("muted") != 0));
                    }
                }
                return result;
            } catch (SQLException e2) {
                throw new IllegalStateException("查询群成员失败: " + e2.getMessage(), e2);
            }
        }
    }

    @Override
    public synchronized long createJoinRequest(long groupId, long userId) {
        if (hasJoinRequest(groupId, userId)) return 0;
        String sql = "INSERT INTO join_requests (group_id, user_id, created_ms) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            // 并发重复申请等情况下视为已申请
            return 0;
        }
    }

    @Override
    public synchronized boolean hasJoinRequest(long groupId, long userId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM join_requests WHERE group_id = ? AND user_id = ?")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询入群申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<JoinRequestInfo> pendingJoinRequests(long managerId) {
        List<JoinRequestInfo> result = new ArrayList<>();
        String sql = "SELECT r.id, r.group_id, g.name, r.user_id, u.username FROM join_requests r " +
                "JOIN groups g ON g.id = r.group_id " +
                "JOIN users u ON u.id = r.user_id " +
                "JOIN group_members m ON m.group_id = r.group_id AND m.user_id = ? " +
                "WHERE m.role IN ('owner', 'admin') ORDER BY r.id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, managerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new JoinRequestInfo(rs.getLong("id"), rs.getLong("group_id"),
                            rs.getString("name"), rs.getLong("user_id"), rs.getString("username")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("查询入群申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized JoinRequestInfo findJoinRequest(long id) {
        String sql = "SELECT r.id, r.group_id, g.name, r.user_id, u.username FROM join_requests r " +
                "JOIN groups g ON g.id = r.group_id JOIN users u ON u.id = r.user_id WHERE r.id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new JoinRequestInfo(rs.getLong("id"), rs.getLong("group_id"),
                        rs.getString("name"), rs.getLong("user_id"), rs.getString("username"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询入群申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void deleteJoinRequest(long id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM join_requests WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除入群申请失败: " + e.getMessage(), e);
        }
    }

    // ---------------- 令牌 ----------------

    @Override
    public synchronized void saveToken(String token, long userId) {
        try (PreparedStatement ps = connection.prepareStatement(upsertTokenSql())) {
            ps.setString(1, token);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存令牌失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Long resolveToken(String token) {
        String sql = "SELECT user_id FROM tokens WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("user_id") : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询令牌失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void removeToken(String token) {
        String sql = "DELETE FROM tokens WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除令牌失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("[Database] 关闭连接失败: " + e.getMessage());
        }
    }
}
