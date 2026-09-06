package top.mcocet;

import top.mcocet.server.Auth;
import top.mcocet.server.CertUtil;
import top.mcocet.server.ChatServer;
import top.mcocet.server.Database;
import top.mcocet.server.RealtimeHub;
import top.mcocet.server.ServerConfig;
import top.mcocet.server.WsChatServer;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * LS Chat Server 入口：读取 server.properties 配置，
 * 按通信模式（http / websocket）启动对应服务。两种模式均全程 TLS 加密。
 */
public class Main {

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.load();
        if (config.port() < 1024) {
            System.out.println("警告: 使用了特权端口 " + config.port() + "，建议使用 1024 以上的高端端口");
        }

        Path dataDir = Paths.get(config.dataDir());
        SSLContext sslContext = CertUtil.buildOrCreateSslContext(dataDir);
        Database database = Database.create(dataDir, config);
        Auth auth = new Auth(database);
        RealtimeHub hub = new RealtimeHub(database);

        System.out.println("  LS Chat Server 已启动");
        System.out.println("  通信模式: " + ("websocket".equals(config.mode()) ? "WebSocket (WSS)" : "HTTP (HTTPS)"));
        System.out.println("  端口: " + config.port());
        System.out.println("  存储后端: " + database.describe());
        System.out.println("  数据目录: " + dataDir.toAbsolutePath());

        if ("websocket".equals(config.mode())) {
            WsChatServer server = new WsChatServer(config.port(), database, auth, hub, sslContext);
            server.start();
            registerShutdownHook(() -> {
                System.out.println("[Main] 正在关闭...");
                server.shutdown();
                database.close();
            });
        } else {
            ChatServer server = new ChatServer(dataDir, database, auth, hub);
            server.start(config.port(), sslContext);
            registerShutdownHook(() -> {
                System.out.println("[Main] 正在关闭...");
                server.stop();
                database.close();
            });
        }
    }

    private static void registerShutdownHook(Runnable action) {
        Runtime.getRuntime().addShutdownHook(new Thread(action, "shutdown-hook"));
    }
}
