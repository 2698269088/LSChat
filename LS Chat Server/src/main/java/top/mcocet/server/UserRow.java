package top.mcocet.server;

/** 数据库中的用户记录（含密码盐与哈希，仅服务端内部使用）。 */
public record UserRow(long id, String username, String salt, String passwordHash) {
}
