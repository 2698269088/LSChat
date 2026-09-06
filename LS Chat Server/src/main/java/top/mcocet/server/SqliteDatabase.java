package top.mcocet.server;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/** 默认存储：内嵌 SQLite 数据库（lschat-data/lschat.db），无需额外安装。 */
public class SqliteDatabase extends JdbcDatabase {

    public SqliteDatabase(Path dataDir) throws Exception {
        super("jdbc:sqlite:" + dataDir.resolve("lschat.db").toAbsolutePath().toString().replace('\\', '/'), "", "");
    }

    @Override
    public String describe() {
        return "SQLite (内嵌, lschat-data/lschat.db)";
    }

    @Override
    protected List<String> ddlStatements() {
        return Arrays.asList(
                "CREATE TABLE IF NOT EXISTS users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "username TEXT NOT NULL, " +
                        "salt TEXT NOT NULL, " +
                        "pass TEXT NOT NULL, " +
                        "avatar TEXT NOT NULL DEFAULT '', " +
                        "signature TEXT NOT NULL DEFAULT '', " +
                        "status TEXT NOT NULL DEFAULT '')",
                "CREATE TABLE IF NOT EXISTS messages (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "sender INTEGER NOT NULL, " +
                        "recipient INTEGER NOT NULL, " +
                        "group_id INTEGER NOT NULL DEFAULT 0, " +
                        "type TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "time_ms INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS tokens (" +
                        "token TEXT PRIMARY KEY, " +
                        "user_id INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS groups (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "owner_id INTEGER NOT NULL, " +
                        "join_policy TEXT NOT NULL DEFAULT 'approval', " +
                        "muted_all INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS group_members (" +
                        "group_id INTEGER NOT NULL, " +
                        "user_id INTEGER NOT NULL, " +
                        "role TEXT NOT NULL DEFAULT 'member', " +
                        "muted INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY (group_id, user_id))",
                "CREATE TABLE IF NOT EXISTS join_requests (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "group_id INTEGER NOT NULL, " +
                        "user_id INTEGER NOT NULL, " +
                        "created_ms INTEGER NOT NULL, " +
                        "UNIQUE (group_id, user_id))",
                "CREATE TABLE IF NOT EXISTS friends (" +
                        "user_id INTEGER NOT NULL, " +
                        "friend_id INTEGER NOT NULL, " +
                        "created_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY (user_id, friend_id))",
                "CREATE TABLE IF NOT EXISTS friend_requests (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "from_id INTEGER NOT NULL, " +
                        "to_id INTEGER NOT NULL, " +
                        "created_ms INTEGER NOT NULL, " +
                        "UNIQUE (from_id, to_id))");
    }

    @Override
    protected String upsertTokenSql() {
        return "INSERT OR REPLACE INTO tokens (token, user_id) VALUES (?, ?)";
    }

    @Override
    protected String insertFriendSql() {
        return "INSERT OR IGNORE INTO friends (user_id, friend_id, created_ms) VALUES (?, ?, ?)";
    }

    @Override
    protected void runMigrations() throws SQLException {
        // 早期版本 messages 表没有 group_id 列，这里自动补列
        boolean hasGroupId = false;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(messages)")) {
            while (rs.next()) {
                if ("group_id".equals(rs.getString("name"))) {
                    hasGroupId = true;
                    break;
                }
            }
        }
        if (!hasGroupId) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE messages ADD COLUMN group_id INTEGER NOT NULL DEFAULT 0");
            }
            System.out.println("[Database] 已迁移：messages 表添加 group_id 列");
        }
        migrateUsersTable();
    }

    /** users 表升级：补充 avatar/signature/status 列；去除 username 唯一约束（允许重名，旧库需重建表）。 */
    private void migrateUsersTable() throws SQLException {
        boolean hasAvatar = false;
        boolean hasSignature = false;
        boolean hasStatus = false;
        boolean uniqueOnUsername = false;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(users)")) {
            while (rs.next()) {
                String column = rs.getString("name");
                if ("avatar".equals(column)) hasAvatar = true;
                if ("signature".equals(column)) hasSignature = true;
                if ("status".equals(column)) hasStatus = true;
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA index_list(users)")) {
            while (rs.next()) {
                if (rs.getInt("unique") != 1) continue;
                String indexName = rs.getString("name");
                try (Statement st2 = connection.createStatement();
                     ResultSet ci = st2.executeQuery("PRAGMA index_info(" + indexName + ")")) {
                    int columns = 0;
                    boolean usernameOnly = true;
                    while (ci.next()) {
                        columns++;
                        if (!"username".equals(ci.getString("name"))) usernameOnly = false;
                    }
                    if (columns == 1 && usernameOnly) uniqueOnUsername = true;
                }
            }
        }
        if (!hasAvatar) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD COLUMN avatar TEXT NOT NULL DEFAULT ''");
            }
            System.out.println("[Database] 已迁移：users 表添加 avatar 列");
        }
        if (!hasSignature) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD COLUMN signature TEXT NOT NULL DEFAULT ''");
            }
            System.out.println("[Database] 已迁移：users 表添加 signature 列");
        }
        if (!hasStatus) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT ''");
            }
            System.out.println("[Database] 已迁移：users 表添加 status 列");
        }
        if (uniqueOnUsername) {
            // SQLite 无法删除唯一约束，重建 users 表（数据原样保留）
            boolean oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users_mig (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "username TEXT NOT NULL, " +
                        "salt TEXT NOT NULL, " +
                        "pass TEXT NOT NULL, " +
                        "avatar TEXT NOT NULL DEFAULT '', " +
                        "signature TEXT NOT NULL DEFAULT '', " +
                        "status TEXT NOT NULL DEFAULT '')");
                statement.execute("INSERT INTO users_mig (id, username, salt, pass, avatar, signature, status) " +
                        "SELECT id, username, salt, pass, avatar, signature, status FROM users");
                statement.execute("DROP TABLE users");
                statement.execute("ALTER TABLE users_mig RENAME TO users");
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAuto);
            }
            System.out.println("[Database] 已迁移：users 表去除 username 唯一约束（允许重名）");
        }
    }
}
