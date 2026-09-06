using System;
using System.Collections;
using System.Collections.Generic;
using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace LS_Chat
{
    public class ApiException : Exception
    {
        public ApiException(string message) : base(message)
        {
        }
    }

    /// <summary>
    /// 网络接口封装，支持两种通信协议（默认 WebSocket）：
    /// - websocket：WSS 长连接，JSON 指令 + 实时消息推送（见服务端 WsChatServer 协议）；
    /// - http：HTTPS + JSON REST 接口，客户端轮询。
    /// 两种协议均使用 TLS 加密，并采用 TOFU 证书锁定（首次连接记录服务器证书
    /// SHA-256 指纹，之后每次连接校验一致），防止中间人攻击。
    /// </summary>
    public class ApiClient
    {
        // 图片消息可达数 MB，必须放开 JSON 长度限制
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer
        {
            MaxJsonLength = int.MaxValue
        };

        private readonly AppConfig _config;
        private readonly HttpClient _http;
        private string _pendingCertError;
        private WsClient _ws;

        /// <summary>WebSocket 模式下服务端实时推送的新消息。</summary>
        public event Action<ChatMessage> MessageReceived;

        /// <summary>是否使用 WebSocket 协议（配置为 http 时走 HTTPS 轮询）。</summary>
        public bool UseWebSocket
        {
            get { return !string.Equals(_config.Protocol, "http", StringComparison.OrdinalIgnoreCase); }
        }

        public bool WebSocketConnected
        {
            get { return _ws != null && _ws.Connected; }
        }

        public ApiClient(AppConfig config)
        {
            _config = config;
            var handler = new HttpClientHandler();
            handler.ServerCertificateCustomValidationCallback = ValidateServerCertificate;
            _http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(15) };
        }

        // ---------------- WebSocket 管理 ----------------

        /// <summary>确保 WebSocket 连接已建立（WS 模式）；HTTP 模式下为空操作。</summary>
        public async Task EnsureWebSocketAsync()
        {
            if (!UseWebSocket) return;
            if (_ws != null && _ws.Connected) return;
            CloseWebSocket();
            var ws = new WsClient(_config);
            ws.MessageReceived += HandleWsPush;
            try
            {
                await ws.ConnectAsync(_config.ServerHost, _config.ServerPort, _config.Token);
                _ws = ws;
            }
            catch
            {
                ws.MessageReceived -= HandleWsPush;
                ws.Dispose();
                throw;
            }
        }

        /// <summary>等待当前连接断开（用于重连循环）；未连接时立即完成。</summary>
        public Task WaitForWebSocketDisconnectAsync()
        {
            var ws = _ws;
            if (ws == null || !ws.Connected) return Task.FromResult(true);
            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            ws.Disconnected += () => tcs.TrySetResult(true);
            return tcs.Task;
        }

        public void CloseWebSocket()
        {
            var ws = _ws;
            _ws = null;
            if (ws != null)
            {
                ws.MessageReceived -= HandleWsPush;
                ws.Dispose();
            }
        }

        private void HandleWsPush(Dictionary<string, object> push)
        {
            object message;
            if (!push.TryGetValue("message", out message) || message == null) return;
            var handler = MessageReceived;
            if (handler == null) return;
            try
            {
                handler(ParseMessage((Dictionary<string, object>)message));
            }
            catch
            {
                // 单个推送解析失败不影响接收循环
            }
        }

        // ---------------- 统一指令入口 ----------------

        /// <summary>
        /// 统一指令入口：WebSocket 模式走 WSS 长连接指令，HTTP 模式映射到对应 REST 接口。
        /// 指令名与服务端 WsChatServer 一致。
        /// </summary>
        private async Task<Dictionary<string, object>> Invoke(string cmd,
            Dictionary<string, object> body = null, bool auth = true)
        {
            if (UseWebSocket)
            {
                await EnsureWebSocketAsync();
                return await _ws.InvokeAsync(cmd, body);
            }
            return await HttpInvoke(cmd, body, auth);
        }

        private async Task<Dictionary<string, object>> HttpInvoke(string cmd,
            Dictionary<string, object> body, bool auth)
        {
            switch (cmd)
            {
                case "ping": return await Request("/api/ping", "GET", null, false);
                case "register": return await Request("/api/register", "POST", body, false);
                case "login": return await Request("/api/login", "POST", body, false);
                case "users": return await Request("/api/users", "GET");
                case "avatar.get": return await Request("/api/avatar?id=" + ToLong(body["id"]), "GET");
                case "avatar.set": return await Request("/api/avatar", "POST", body);
                case "profile.get": return await Request("/api/profile", "GET");
                case "profile.set": return await Request("/api/profile", "POST", body);
                case "send": return await Request("/api/messages", "POST", body);
                case "poll": return await Request("/api/messages?after=" + ToLong(body["after"]), "GET");
                case "history":
                    string target = body.ContainsKey("group")
                        ? "group=" + ToLong(body["group"])
                        : "peer=" + ToLong(body["peer"]);
                    return await Request("/api/messages?" + target + "&after=" + ToLong(body["after"]), "GET");
                case "friends.lookup": return await Request("/api/friends/lookup?id=" + ToLong(body["id"]), "GET");
                case "friends.request": return await Request("/api/friends/request", "POST", body);
                case "friends.requests": return await Request("/api/friends/requests", "GET");
                case "friends.handle": return await Request("/api/friends/requests", "POST", body);
                case "groups": return await Request("/api/groups", "GET");
                case "groups.create": return await Request("/api/groups/create", "POST", body);
                case "groups.search": return await Request("/api/groups/search?id=" + ToLong(body["id"]), "GET");
                case "groups.join": return await Request("/api/groups/join", "POST", body);
                case "groups.requests": return await Request("/api/groups/requests", "GET");
                case "groups.handle": return await Request("/api/groups/requests", "POST", body);
                case "groups.rename": return await Request("/api/groups/rename", "POST", body);
                case "groups.settings": return await Request("/api/groups/settings", "POST", body);
                case "groups.mute": return await Request("/api/groups/mute", "POST", body);
                case "groups.role": return await Request("/api/groups/role", "POST", body);
                case "groups.members": return await Request("/api/groups/members?groupId=" + ToLong(body["groupId"]), "GET");
                default: throw new ApiException("未知指令: " + cmd);
            }
        }

        private bool ValidateServerCertificate(HttpRequestMessage request, X509Certificate2 certificate,
            X509Chain chain, SslPolicyErrors sslPolicyErrors)
        {
            _pendingCertError = _config.ValidateServerCertificate(certificate);
            return _pendingCertError == null;
        }

        private string BaseUrl
        {
            get { return "https://" + _config.ServerHost + ":" + _config.ServerPort; }
        }

        private async Task<Dictionary<string, object>> Request(string path, string method,
            Dictionary<string, object> body = null, bool auth = true)
        {
            _pendingCertError = null;
            try
            {
                using (var request = new HttpRequestMessage(new HttpMethod(method), BaseUrl + path))
                {
                    if (auth && !string.IsNullOrEmpty(_config.Token))
                    {
                        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + _config.Token);
                    }
                    if (body != null)
                    {
                        request.Content = new StringContent(Json.Serialize(body), Encoding.UTF8, "application/json");
                    }
                    using (HttpResponseMessage response = await _http.SendAsync(request))
                    {
                        string text = await response.Content.ReadAsStringAsync();
                        if (!response.IsSuccessStatusCode || string.IsNullOrEmpty(text))
                        {
                            throw new ApiException("连接服务器失败 (HTTP " + (int)response.StatusCode + ")");
                        }
                        var obj = Json.Deserialize<Dictionary<string, object>>(text);
                        if (obj == null) throw new ApiException("服务器返回无效数据");
                        return obj;
                    }
                }
            }
            catch (ApiException)
            {
                throw;
            }
            catch (TaskCanceledException)
            {
                throw new ApiException("连接服务器超时");
            }
            catch (HttpRequestException)
            {
                throw new ApiException(_pendingCertError ?? "连接服务器失败，请检查网络或服务器地址");
            }
        }

        private static Dictionary<string, object> Checked(Dictionary<string, object> response)
        {
            object code;
            if (response.TryGetValue("code", out code) && Convert.ToInt64(code) == 0)
            {
                return response;
            }
            object message;
            throw new ApiException(response.TryGetValue("message", out message) && message != null
                ? message.ToString()
                : "未知错误");
        }

        private static long ToLong(object value)
        {
            return value == null ? 0L : Convert.ToInt64(value);
        }

        private static ChatMessage ParseMessage(Dictionary<string, object> item)
        {
            return new ChatMessage
            {
                id = ToLong(item["id"]),
                from = ToLong(item["from"]),
                to = ToLong(item["to"]),
                group = item.ContainsKey("group") ? ToLong(item["group"]) : 0,
                type = item["type"].ToString(),
                content = item["content"].ToString(),
                time = ToLong(item["time"])
            };
        }

        private static List<ChatMessage> ParseMessages(Dictionary<string, object> response)
        {
            var list = (IList)response["messages"];
            var result = new List<ChatMessage>(list.Count);
            foreach (Dictionary<string, object> item in list)
            {
                result.Add(ParseMessage(item));
            }
            return result;
        }

        // ---------------- 基础 ----------------

        public async Task<string> Ping()
        {
            Dictionary<string, object> response = await Invoke("ping", null, false);
            return response["name"] + " v" + response["version"];
        }

        public async Task<User> Register(string username, string password)
        {
            Dictionary<string, object> response = Checked(await Invoke("register",
                new Dictionary<string, object>
                {
                    ["username"] = username,
                    ["password"] = password
                }, false));
            var user = (Dictionary<string, object>)response["user"];
            return new User { id = ToLong(user["id"]), username = user["username"].ToString() };
        }

        public async Task Login(long userId, string password)
        {
            Dictionary<string, object> response = Checked(await Invoke("login",
                new Dictionary<string, object>
                {
                    ["userId"] = userId,
                    ["password"] = password
                }, false));
            _config.Token = response["token"].ToString();
            var user = (Dictionary<string, object>)response["user"];
            _config.UserId = ToLong(user["id"]);
            _config.UserName = user["username"].ToString();
            _config.Save();
        }

        public async Task<List<User>> Users()
        {
            Dictionary<string, object> response = Checked(await Invoke("users"));
            var list = (IList)response["users"];
            var result = new List<User>(list.Count);
            foreach (Dictionary<string, object> item in list)
            {
                result.Add(new User
                {
                    id = ToLong(item["id"]),
                    username = item["username"].ToString(),
                    signature = item.ContainsKey("signature") && item["signature"] != null
                        ? item["signature"].ToString() : "",
                    status = item.ContainsKey("status") && item["status"] != null
                        ? item["status"].ToString() : ""
                });
            }
            return result;
        }

        // ---------------- 个人资料 ----------------

        /// <summary>取自己的签名与状态。</summary>
        public async Task<UserProfile> GetProfile()
        {
            Dictionary<string, object> response = Checked(await Invoke("profile.get"));
            var profile = (Dictionary<string, object>)response["profile"];
            return new UserProfile
            {
                signature = profile.ContainsKey("signature") && profile["signature"] != null
                    ? profile["signature"].ToString() : "",
                status = profile.ContainsKey("status") && profile["status"] != null
                    ? profile["status"].ToString() : ""
            };
        }

        /// <summary>设置签名与状态。</summary>
        public async Task SetProfile(string signature, string status)
        {
            Checked(await Invoke("profile.set",
                new Dictionary<string, object>
                {
                    ["signature"] = signature,
                    ["status"] = status
                }));
        }

        // ---------------- 消息 ----------------

        /// <summary>拉取与指定用户之间的历史/增量消息。</summary>
        public async Task<List<ChatMessage>> FetchMessages(long peer, long after)
        {
            Dictionary<string, object> response = Checked(await Invoke("history",
                new Dictionary<string, object> { ["peer"] = peer, ["after"] = after }));
            return ParseMessages(response);
        }

        /// <summary>拉取所有发给我的新消息（后台兜底用，不带 peer 参数）。</summary>
        public async Task<List<ChatMessage>> FetchIncoming(long after)
        {
            Dictionary<string, object> response = Checked(await Invoke("poll",
                new Dictionary<string, object> { ["after"] = after }));
            return ParseMessages(response);
        }

        public async Task<ChatMessage> Send(long peer, string type, string content)
        {
            Dictionary<string, object> response = Checked(await Invoke("send",
                new Dictionary<string, object>
                {
                    ["peer"] = peer,
                    ["type"] = type,
                    ["content"] = content
                }));
            return ParseMessage((Dictionary<string, object>)response["message"]);
        }

        // ---------------- 头像 ----------------

        /// <summary>设置自己的头像（JPEG/PNG Base64）。</summary>
        public async Task SetAvatar(string base64)
        {
            Checked(await Invoke("avatar.set",
                new Dictionary<string, object> { ["content"] = base64 }));
        }

        /// <summary>取用户头像（Base64），未设置返回空串。</summary>
        public async Task<string> FetchAvatar(long userId)
        {
            Dictionary<string, object> response =
                Checked(await Invoke("avatar.get", new Dictionary<string, object> { ["id"] = userId }));
            return response.ContainsKey("avatar") && response["avatar"] != null
                ? response["avatar"].ToString()
                : "";
        }

        // ---------------- 好友 ----------------

        /// <summary>按用户 ID 查找（添加好友前确认对方）。</summary>
        public async Task<User> LookupUser(long id)
        {
            Dictionary<string, object> response =
                Checked(await Invoke("friends.lookup", new Dictionary<string, object> { ["id"] = id }));
            var user = (Dictionary<string, object>)response["user"];
            return new User { id = ToLong(user["id"]), username = user["username"].ToString() };
        }

        /// <summary>发送好友申请：返回提示信息（等待同意或已互为好友）。</summary>
        public async Task<string> SendFriendRequest(long userId)
        {
            Dictionary<string, object> response = Checked(await Invoke("friends.request",
                new Dictionary<string, object> { ["userId"] = userId }));
            return response["message"].ToString();
        }

        /// <summary>发给我的好友申请。</summary>
        public async Task<List<FriendRequest>> FriendRequests()
        {
            Dictionary<string, object> response = Checked(await Invoke("friends.requests"));
            var list = (IList)response["requests"];
            var result = new List<FriendRequest>(list.Count);
            foreach (Dictionary<string, object> item in list)
            {
                result.Add(new FriendRequest
                {
                    id = ToLong(item["id"]),
                    userId = ToLong(item["userId"]),
                    username = item["username"].ToString()
                });
            }
            return result;
        }

        public async Task HandleFriendRequest(long requestId, bool approve)
        {
            Checked(await Invoke("friends.handle",
                new Dictionary<string, object>
                {
                    ["requestId"] = requestId,
                    ["approve"] = approve
                }));
        }

        // ---------------- 群组 ----------------

        private static GroupInfo ParseGroup(Dictionary<string, object> item)
        {
            return new GroupInfo
            {
                id = ToLong(item["id"]),
                name = item["name"].ToString(),
                ownerId = ToLong(item["ownerId"]),
                joinPolicy = item["joinPolicy"].ToString(),
                mutedAll = ToBool(item["mutedAll"]),
                memberCount = (int)ToLong(item["memberCount"]),
                role = item.ContainsKey("role") && item["role"] != null ? item["role"].ToString() : null
            };
        }

        private static bool ToBool(object value)
        {
            if (value == null) return false;
            if (value is bool) return (bool)value;
            return Convert.ToInt64(value) != 0;
        }

        /// <summary>创建群，返回群信息。</summary>
        public async Task<GroupInfo> CreateGroup(string name, string joinPolicy)
        {
            Dictionary<string, object> response = Checked(await Invoke("groups.create",
                new Dictionary<string, object>
                {
                    ["name"] = name,
                    ["joinPolicy"] = joinPolicy
                }));
            return ParseGroup((Dictionary<string, object>)response["group"]);
        }

        /// <summary>我加入的群。</summary>
        public async Task<List<GroupInfo>> MyGroups()
        {
            Dictionary<string, object> response = Checked(await Invoke("groups"));
            var list = (IList)response["groups"];
            var result = new List<GroupInfo>(list.Count);
            foreach (Dictionary<string, object> item in list)
            {
                result.Add(ParseGroup(item));
            }
            return result;
        }

        /// <summary>按群 ID 搜索。</summary>
        public async Task<GroupInfo> SearchGroup(long id)
        {
            Dictionary<string, object> response =
                Checked(await Invoke("groups.search", new Dictionary<string, object> { ["id"] = id }));
            return ParseGroup((Dictionary<string, object>)response["group"]);
        }

        /// <summary>加入群：返回提示信息（直接加入或已提交申请）。</summary>
        public async Task<string> JoinGroup(long groupId)
        {
            Dictionary<string, object> response = Checked(await Invoke("groups.join",
                new Dictionary<string, object> { ["groupId"] = groupId }));
            return response["message"].ToString();
        }

        /// <summary>我管理的群的入群申请。</summary>
        public async Task<List<JoinRequest>> GroupRequests()
        {
            Dictionary<string, object> response = Checked(await Invoke("groups.requests"));
            var list = (IList)response["requests"];
            var result = new List<JoinRequest>(list.Count);
            foreach (Dictionary<string, object> item in list)
            {
                result.Add(new JoinRequest
                {
                    id = ToLong(item["id"]),
                    groupId = ToLong(item["groupId"]),
                    groupName = item["groupName"].ToString(),
                    userId = ToLong(item["userId"]),
                    username = item["username"].ToString()
                });
            }
            return result;
        }

        public async Task HandleGroupRequest(long requestId, bool approve)
        {
            Checked(await Invoke("groups.handle",
                new Dictionary<string, object>
                {
                    ["requestId"] = requestId,
                    ["approve"] = approve
                }));
        }

        public async Task RenameGroup(long groupId, string name)
        {
            Checked(await Invoke("groups.rename",
                new Dictionary<string, object> { ["groupId"] = groupId, ["name"] = name }));
        }

        public async Task UpdateGroupSettings(long groupId, string joinPolicy, bool mutedAll)
        {
            Checked(await Invoke("groups.settings",
                new Dictionary<string, object>
                {
                    ["groupId"] = groupId,
                    ["joinPolicy"] = joinPolicy,
                    ["mutedAll"] = mutedAll
                }));
        }

        public async Task SetGroupMute(long groupId, long userId, bool muted)
        {
            Checked(await Invoke("groups.mute",
                new Dictionary<string, object>
                {
                    ["groupId"] = groupId,
                    ["userId"] = userId,
                    ["muted"] = muted
                }));
        }

        /// <summary>设置成员角色（仅群主）：admin / member。</summary>
        public async Task SetGroupRole(long groupId, long userId, string role)
        {
            Checked(await Invoke("groups.role",
                new Dictionary<string, object>
                {
                    ["groupId"] = groupId,
                    ["userId"] = userId,
                    ["role"] = role
                }));
        }

        public async Task<List<GroupMember>> GroupMembers(long groupId)
        {
            Dictionary<string, object> response =
                Checked(await Invoke("groups.members", new Dictionary<string, object> { ["groupId"] = groupId }));
            var list = (IList)response["members"];
            var result = new List<GroupMember>(list.Count);
            foreach (Dictionary<string, object> item in list)
            {
                result.Add(new GroupMember
                {
                    id = ToLong(item["id"]),
                    username = item["username"].ToString(),
                    role = item["role"].ToString(),
                    muted = ToBool(item["muted"])
                });
            }
            return result;
        }

        public async Task<List<ChatMessage>> FetchGroupMessages(long groupId, long after)
        {
            Dictionary<string, object> response = Checked(await Invoke("history",
                new Dictionary<string, object> { ["group"] = groupId, ["after"] = after }));
            return ParseMessages(response);
        }

        public async Task<ChatMessage> SendGroupMessage(long groupId, string type, string content)
        {
            Dictionary<string, object> response = Checked(await Invoke("send",
                new Dictionary<string, object>
                {
                    ["group"] = groupId,
                    ["type"] = type,
                    ["content"] = content
                }));
            return ParseMessage((Dictionary<string, object>)response["message"]);
        }
    }
}
