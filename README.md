# LS Chat

跨平台即时通讯系统：一个 Java 服务端 + Windows / Android 双客户端，支持私聊、群聊、文字 / 图片 / 视频消息、好友与群组管理，通信全程 TLS 加密。

## 架构

```
┌────────────────────┐         ┌───────────────────────────┐
│  Windows 客户端     │         │  LS Chat Server (Java)     │
│  C# WinForms (.NET │   WSS   │  · WebSocket 模式（默认）   │
│  Framework 4.8)    │ ──────► │    WSS + JSON 指令 + 推送   │
├────────────────────┤   or    │  · HTTP 模式               │
│  Android 客户端     │  HTTPS  │    HTTPS + JSON REST 轮询  │
│  Kotlin + Compose  │ ──────► │  · SQLite / MySQL 存储     │
└────────────────────┘         │  · 自签名 TLS 证书（TOFU）  │
                               └───────────────────────────┘
```

- **服务端**：[LS Chat Server](LS%20Chat%20Server)（Java 17+，Maven）
  - `server.properties` 配置通信模式、端口、数据库
  - 密码 PBKDF2 加盐哈希存储，Bearer Token 鉴权
  - WebSocket 模式下实时推送新消息；HTTP 模式下由客户端轮询
- **Windows 客户端**：[LS Chat](LS%20Chat)（C# WinForms，.NET Framework 4.8）
  - 系统托盘常驻、气泡通知、图片/视频查看器
  - WebSocket 模式下手写 RFC 6455 协议栈（WSS），断线自动重连
- **Android 客户端**：[app](app)（Kotlin + Jetpack Compose，minSdk 24）
  - 前台服务保活、系统通知、图片压缩发送、视频内嵌播放

## 功能

- 用户注册（用户名可重名，用户 ID 登录）与登录
- 私聊 / 群聊：文字、图片、视频（Base64，视频最大约 40MB）
- 好友：按 ID 查找、申请、同意/拒绝
- 群组：创建、搜索、入群审核、改名、入群方式（开放/审核）、全员禁言、成员禁言、管理员角色
- 个人资料：签名与状态；头像上传（JPEG/PNG），多端懒加载缓存
- 实时消息推送（WebSocket 模式）+ 轮询兜底

## 通信协议

支持两种通信模式，**默认均为 WebSocket**，可在服务端配置文件与客户端设置中切换（两端需保持一致）：

| 模式 | 传输 | 特点 |
|------|------|------|
| `websocket`（默认） | WSS 长连接 | JSON 指令 + 服务端实时推送，断线自动重连 |
| `http` | HTTPS | REST 接口，客户端定时轮询 |

- 所有通信均走 TLS 加密（自签名证书）。
- 客户端采用 TOFU 证书锁定：首次连接记录服务器证书（Android 记完整证书 / Windows 记 SHA-256 指纹），之后每次连接校验一致，防中间人攻击。服务器换证书后需在客户端「清除证书锁定」。

### WebSocket 指令协议

指令为一行 JSON 文本帧：`{"cmd": "<指令>", "req": <序号>, ...参数}`。

| 指令 | 说明 |
|------|------|
| `ping` / `register` / `login` / `auth` | 连通测试 / 注册 / 登录 / 令牌认证 |
| `users` | 好友列表 |
| `send` | 发消息（`peer` 或 `group` + `type` + `content`） |
| `poll` / `history` | 拉取新消息 / 历史消息（`after` 增量） |
| `avatar.get` / `avatar.set`、`profile.get` / `profile.set` | 头像与个人资料 |
| `friends.lookup` / `friends.request` / `friends.requests` / `friends.handle` | 好友相关 |
| `groups` / `groups.create` / `groups.search` / `groups.join` / `groups.requests` / `groups.handle` / `groups.rename` / `groups.settings` / `groups.mute` / `groups.role` / `groups.members` | 群组相关 |

- 响应：`{"type": "<指令>", "code": 0, ...}`，并原样回显 `req` 序号用于请求-响应关联；错误为 `{"type": "error", "code": ..., "message": ...}`。
- 服务端主动推送：`{"type": "message", "message": {...}}`（无 `req` 字段）。
- 连接时可在握手头带 `Authorization: Bearer <token>` 直接认证，也可用 `login` / `auth` 指令认证。

## 服务端配置

`server.properties`（工作目录下，首次启动自动生成模板）：

```properties
mode=websocket          # 通信模式：websocket（默认）/ http
port=18443              # 监听端口
data.dir=lschat-data    # 数据目录（SQLite 数据库与 TLS 证书）
db.type=sqlite          # sqlite / mysql
db.url=jdbc:mysql://localhost:3306/lschat   # db.type=mysql 时生效
db.user=root
db.pass=
```

- 配置文件优先，未配置项回退到环境变量 `LSCHAT_DB` / `LSCHAT_DB_URL` / `LSCHAT_DB_USER` / `LSCHAT_DB_PASS`。
- MySQL 需先创建数据库，建表语句首次启动自动执行。

## 构建与运行

### 服务端

```bash
cd "LS Chat Server"
mvn package
java -jar target/LSChatServer-1.0-SNAPSHOT.jar
```

### Windows 客户端

用 Visual Studio 打开 [LS Chat.sln](LS%20Chat/LS%20Chat.sln)（需 .NET Framework 4.8 开发者包），生成并运行。首次启动配置服务器地址与协议。

### Android 客户端

用 Android Studio 打开根目录 Gradle 项目，`app` 模块运行/打包：

```bash
gradle :app:assembleDebug
```

首次启动配置服务器地址与协议；登录后前台服务自动启动接收消息通知。

## 目录结构

```
LSChat/
├── LS Chat Server/     # Java 服务端（Maven）
│   └── src/main/java/top/mcocet/server/
│       ├── ChatServer.java    # HTTP 模式（HTTPS + REST）
│       ├── WsChatServer.java  # WebSocket 模式（WSS + 指令 + 推送）
│       ├── ServerConfig.java  # server.properties 加载
│       ├── RealtimeHub.java   # 在线连接与实时推送中心
│       ├── Database.java / *Database.java  # SQLite / MySQL 实现
│       └── CertUtil.java      # 自签名 TLS 证书
├── LS Chat/            # Windows 客户端（C# WinForms）
│   └── LS Chat/
│       ├── ApiClient.cs       # 双协议统一网络入口
│       ├── WsClient.cs        # 手写 WebSocket 协议栈（WSS）
│       ├── AppConfig.cs       # 本地配置（含 TOFU 指纹）
│       └── MainForm.cs        # 主界面（托盘 + 推送/轮询）
└── app/                # Android 客户端（Kotlin + Compose）
    └── src/main/java/top/mcocet/lschat/
        ├── data/ApiClient.kt  # 双协议统一网络入口
        ├── data/WsTransport.kt# OkHttp WebSocket 传输层
        ├── service/MessagePollingService.kt  # 前台服务（推送 + 兜底轮询）
        └── ui/                # 各界面（Compose）
```

## 安全说明

- 全链路 TLS 加密：HTTPS / WSS；服务端证书首次启动自动生成（RSA 2048，有效期 10 年）。
- 客户端 TOFU 证书锁定防中间人；证书变更会被拒绝并提示。
- 密码使用 PBKDF2WithHmacSHA256（12 万轮迭代 + 16 字节盐）哈希存储，服务器不保存明文。
- 登录令牌为 256 位随机值，服务端可校验、可失效。
