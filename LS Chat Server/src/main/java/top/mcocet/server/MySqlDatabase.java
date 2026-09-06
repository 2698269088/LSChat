package top.mcocet.server;

import java.util.Arrays;
import java.util.List;

/**
 * MySQL 存储（可选）。使用前需创建数据库，例如：
 * CREATE DATABASE lschat DEFAULT CHARSET utf8mb4;
 * 并通过环境变量 LSCHAT_DB=mysql、LSCHAT_DB_URL、LSCHAT_DB_USER、LSCHAT_DB_PASS 启用。
 */
public class MySqlDatabase extends JdbcDatabase {

    private final String jdbcUrl;

    public MySqlDatabase(String jdbcUrl, String user, String password) throws Exception {
        super(jdbcUrl, user, password);
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public String describe() {
        return "MySQL (" + jdbcUrl + ")";
    }

    @Override
    protected List<String> ddlStatements() {
        return Arrays.asList(
                "CREATE TABLE IF NOT EXISTS users (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
                        "username VARCHAR(32) NOT NULL, " +
                        "salt VARCHAR(64) NOT NULL, " +
                        "pass VARCHAR(128) NOT NULL, " +
                        "avatar MEDIUMTEXT NOT NULL, " +
                        "signature VARCHAR(30) NOT NULL DEFAULT '', " +
                        "status VARCHAR(20) NOT NULL DEFAULT '') DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS messages (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
                        "sender BIGINT NOT NULL, " +
                        "recipient BIGINT NOT NULL, " +
                        "group_id BIGINT NOT NULL DEFAULT 0, " +
                        "type VARCHAR(16) NOT NULL, " +
                        "content MEDIUMTEXT NOT NULL, " +
                        "time_ms BIGINT NOT NULL, " +
                        "INDEX idx_pair (sender, recipient, id)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS tokens (" +
                        "token VARCHAR(64) PRIMARY KEY, " +
                        "user_id BIGINT NOT NULL) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS groups (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
                        "name VARCHAR(64) NOT NULL, " +
                        "owner_id BIGINT NOT NULL, " +
                        "join_policy VARCHAR(16) NOT NULL DEFAULT 'approval', " +
                        "muted_all INT NOT NULL DEFAULT 0) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS group_members (" +
                        "group_id BIGINT NOT NULL, " +
                        "user_id BIGINT NOT NULL, " +
                        "role VARCHAR(16) NOT NULL DEFAULT 'member', " +
                        "muted INT NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY (group_id, user_id)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS join_requests (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
                        "group_id BIGINT NOT NULL, " +
                        "user_id BIGINT NOT NULL, " +
                        "created_ms BIGINT NOT NULL, " +
                        "UNIQUE KEY uk_group_user (group_id, user_id)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS friends (" +
                        "user_id BIGINT NOT NULL, " +
                        "friend_id BIGINT NOT NULL, " +
                        "created_ms BIGINT NOT NULL, " +
                        "PRIMARY KEY (user_id, friend_id)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS friend_requests (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
                        "from_id BIGINT NOT NULL, " +
                        "to_id BIGINT NOT NULL, " +
                        "created_ms BIGINT NOT NULL, " +
                        "UNIQUE KEY uk_from_to (from_id, to_id)) DEFAULT CHARSET=utf8mb4");
    }

    @Override
    protected String upsertTokenSql() {
        return "REPLACE INTO tokens (token, user_id) VALUES (?, ?)";
    }

    @Override
    protected String insertFriendSql() {
        return "INSERT IGNORE INTO friends (user_id, friend_id, created_ms) VALUES (?, ?, ?)";
    }

    @Override
    protected void runMigrations() throws java.sql.SQLException {
        boolean hasGroupId = false;
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND COLUMN_NAME = 'group_id'");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            hasGroupId = rs.getInt(1) > 0;
        }
        if (!hasGroupId) {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE messages ADD COLUMN group_id BIGINT NOT NULL DEFAULT 0");
            }
            System.out.println("[Database] 已迁移：messages 表添加 group_id 列");
        }
        migrateUsersTable();
    }

    /** users 表升级：补充 avatar/signature/status 列；删除 username 唯一索引（允许重名）。 */
    private void migrateUsersTable() throws java.sql.SQLException {
        String uniqueIndexName = null;
        for (String column : new String[]{"avatar", "signature", "status"}) {
            boolean hasColumn;
            try (java.sql.PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = ?")) {
                ps.setString(1, column);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    hasColumn = rs.getInt(1) > 0;
                }
            }
            if (!hasColumn) {
                String ddl = "avatar".equals(column)
                        ? "ALTER TABLE users ADD COLUMN avatar MEDIUMTEXT NOT NULL"
                        : "ALTER TABLE users ADD COLUMN " + column + " VARCHAR("
                        + ("signature".equals(column) ? 30 : 20) + ") NOT NULL DEFAULT ''";
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute(ddl);
                }
                System.out.println("[Database] 已迁移：users 表添加 " + column + " 列");
            }
        }
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' " +
                        "AND COLUMN_NAME = 'username' AND NON_UNIQUE = 0");
             java.sql.ResultSet rs = ps.executeQuery()) {
            if (rs.next()) uniqueIndexName = rs.getString(1);
        }
        if (uniqueIndexName != null) {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users DROP INDEX " + uniqueIndexName);
            }
            System.out.println("[Database] 已迁移：users 表删除 username 唯一索引（允许重名）");
        }
    }
}
