package top.mcocet.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 服务端配置文件（server.properties，位于工作目录）。
 * 首次启动时若文件不存在会自动生成带注释的模板。
 * 未在配置文件中设置的项回退到环境变量（LSCHAT_DB*），再回退到默认值。
 *
 * <ul>
 *   <li>mode: http(HTTPS+JSON 轮询) / websocket(WSS 长连接+实时推送)，全程 TLS 加密</li>
 *   <li>port: 监听端口，默认 18443</li>
 *   <li>data.dir: 数据目录（SQLite 数据库与证书），默认 lschat-data</li>
 *   <li>db.type: sqlite / mysql，默认 sqlite</li>
 *   <li>db.url / db.user / db.pass: MySQL 连接信息（db.type=mysql 时生效）</li>
 * </ul>
 */
public record ServerConfig(String mode, int port, String dataDir,
                           String dbType, String dbUrl, String dbUser, String dbPass) {

    private static final String DEFAULT_FILE = "server.properties";

    private static final String TEMPLATE = """
            # LS Chat Server 配置文件
            # 通信模式: http = HTTPS + JSON 接口（客户端轮询）
            #           websocket = WSS + WebSocket 长连接（实时消息推送）
            mode=websocket

            # 监听端口
            port=18443

            # 数据目录（SQLite 数据库与 TLS 证书存放位置）
            data.dir=lschat-data

            # 数据库类型: sqlite / mysql
            db.type=sqlite

            # MySQL 连接信息（db.type=mysql 时生效）
            db.url=jdbc:mysql://localhost:3306/lschat
            db.user=root
            db.pass=
            """;

    /** 从默认路径（工作目录下的 server.properties）加载配置，不存在则生成模板。 */
    public static ServerConfig load() throws IOException {
        return load(Path.of(DEFAULT_FILE));
    }

    public static ServerConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            System.out.println("[ServerConfig] 已加载配置文件: " + file.toAbsolutePath());
        } else {
            Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
            System.out.println("[ServerConfig] 配置文件不存在，已生成模板: " + file.toAbsolutePath());
        }

        String mode = properties.getProperty("mode", "websocket").trim().toLowerCase(Locale.ROOT);
        if (!"http".equals(mode) && !"websocket".equals(mode)) {
            throw new IllegalArgumentException("mode 必须是 http 或 websocket，当前值: " + mode);
        }

        int port = Integer.parseInt(properties.getProperty("port", "18443").trim());
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口不合法: " + port);
        }

        String dataDir = properties.getProperty("data.dir", "lschat-data").trim();
        if (dataDir.isEmpty()) {
            throw new IllegalArgumentException("data.dir 不能为空");
        }

        return new ServerConfig(
                mode,
                port,
                dataDir,
                blankToNull(properties.getProperty("db.type")),
                blankToNull(properties.getProperty("db.url")),
                blankToNull(properties.getProperty("db.user")),
                blankToNull(properties.getProperty("db.pass")));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
