package top.mcocet.server;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 注册、登录与令牌签发。密码使用 PBKDF2WithHmacSHA256 加盐哈希存储。登录使用用户 ID。 */
public class Auth {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Database database;

    public Auth(Database database) {
        this.database = database;
    }

    /** 注册：用户名仅作显示名，允许重名；返回的用户 ID 用于登录。 */
    public synchronized User register(String username, String password) {
        validateUsername(username);
        validatePassword(password);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        UserRow row = database.createUser(username,
                Base64.getEncoder().encodeToString(salt),
                hashPassword(password, salt));
        return new User(row.id(), row.username(), "", "");
    }

    public synchronized String login(long userId, String password) {
        UserRow user = database.findUserById(userId);
        if (user == null) {
            throw new ApiException(401, "用户 ID 或密码错误");
        }
        byte[] salt = Base64.getDecoder().decode(user.salt());
        byte[] expected = Base64.getDecoder().decode(user.passwordHash());
        byte[] actual = hashRaw(password, salt);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiException(401, "用户名或密码错误");
        }
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        database.saveToken(token, user.id());
        return token;
    }

    private String hashPassword(String password, byte[] salt) {
        return Base64.getEncoder().encodeToString(hashRaw(password, salt));
    }

    private byte[] hashRaw(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Arrays.fill(password.toCharArray(), '\0');
            spec.clearPassword();
            return hash;
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }

    private void validateUsername(String username) {
        if (username == null || username.length() < 3 || username.length() > 32
                || !username.matches("[A-Za-z0-9_]+")) {
            throw new ApiException(400, "用户名需为 3-32 位字母、数字或下划线");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new ApiException(400, "密码长度需为 6-64 位");
        }
    }
}
