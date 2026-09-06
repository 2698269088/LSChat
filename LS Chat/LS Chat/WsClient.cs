using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace LS_Chat
{
    /// <summary>
    /// WebSocket（WSS）客户端：.NET Framework 4.8 的 ClientWebSocket 不支持自定义
    /// 服务器证书校验，因此基于 TcpClient + SslStream 直接实现 WebSocket 协议
    /// （RFC 6455：HTTP Upgrade 握手、帧编解码、Ping/Pong、分片消息、客户端掩码），
    /// TLS 层复用 AppConfig 的 TOFU 证书锁定。
    ///
    /// 协议约定（与服务端 WsChatServer 一致）：
    /// - 每条指令为一行 JSON 文本帧，带 "cmd" 与 "req" 序号，响应原样回显 "req"；
    /// - 服务端主动推送的实时消息为 type=message 的无 req 帧，经 <see cref="MessageReceived"/> 抛出。
    /// </summary>
    public class WsClient : IDisposable
    {
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer
        {
            MaxJsonLength = int.MaxValue
        };

        private readonly AppConfig _config;
        private TcpClient _tcp;
        private SslStream _ssl;
        private CancellationTokenSource _cts;
        private Task _receiveTask;
        private long _nextReq;
        private readonly object _sendLock = new object();
        private readonly Dictionary<long, TaskCompletionSource<Dictionary<string, object>>> _pending =
            new Dictionary<long, TaskCompletionSource<Dictionary<string, object>>>();
        private readonly object _pendingLock = new object();

        /// <summary>服务端实时推送的新消息帧（type=message）。</summary>
        public event Action<Dictionary<string, object>> MessageReceived;

        /// <summary>连接正常关闭或断开（含异常断开）后触发，调用方可据此重连。</summary>
        public event Action Disconnected;

        public bool Connected { get; private set; }

        public WsClient(AppConfig config)
        {
            _config = config;
        }

        /// <summary>建立 TCP + TLS 连接并完成 WebSocket 握手；token 非空时随握手发送认证。</summary>
        public async Task ConnectAsync(string host, int port, string token)
        {
            CloseTransport();
            _cts = new CancellationTokenSource();

            _tcp = new TcpClient();
            await Task.Run((Action)(() => _tcp.Connect(host, port)));
            _ssl = new SslStream(_tcp.GetStream(), false, ValidateServerCertificate);
            await Task.Run((Action)(() => _ssl.AuthenticateAsClient(host)));

            string key = Convert.ToBase64String(RandomBytes(16));
            var handshake = new StringBuilder();
            handshake.Append("GET /ws HTTP/1.1\r\n");
            handshake.Append("Host: ").Append(host).Append(':').Append(port).Append("\r\n");
            handshake.Append("Upgrade: websocket\r\n");
            handshake.Append("Connection: Upgrade\r\n");
            handshake.Append("Sec-WebSocket-Key: ").Append(key).Append("\r\n");
            handshake.Append("Sec-WebSocket-Version: 13\r\n");
            if (!string.IsNullOrEmpty(token))
            {
                handshake.Append("Authorization: Bearer ").Append(token).Append("\r\n");
            }
            handshake.Append("\r\n");
            byte[] requestBytes = Encoding.UTF8.GetBytes(handshake.ToString());
            await _ssl.WriteAsync(requestBytes, 0, requestBytes.Length, _cts.Token);
            await _ssl.FlushAsync(_cts.Token);

            string statusLine = await ReadHandshakeStatusAsync();
            if (!statusLine.Contains(" 101"))
            {
                throw new ApiException("WebSocket 握手被拒绝: " + statusLine);
            }

            Connected = true;
            _receiveTask = Task.Run((Func<Task>)ReceiveLoopAsync);
        }

        /// <summary>发送指令并等待对应响应（默认 20 秒超时）。</summary>
        public async Task<Dictionary<string, object>> InvokeAsync(string cmd,
            Dictionary<string, object> body = null, int timeoutMs = 20000)
        {
            if (!Connected || _ssl == null)
            {
                throw new ApiException("WebSocket 未连接");
            }
            long id = Interlocked.Increment(ref _nextReq);
            var payload = body != null
                ? new Dictionary<string, object>(body)
                : new Dictionary<string, object>();
            payload["cmd"] = cmd;
            payload["req"] = id;
            var tcs = new TaskCompletionSource<Dictionary<string, object>>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            lock (_pendingLock)
            {
                _pending[id] = tcs;
            }
            try
            {
                SendText(Json.Serialize(payload));
            }
            catch (Exception e)
            {
                lock (_pendingLock) { _pending.Remove(id); }
                throw new ApiException("WebSocket 发送失败: " + e.Message);
            }
            Task completed = await Task.WhenAny(tcs.Task, Task.Delay(timeoutMs));
            if (completed != tcs.Task)
            {
                lock (_pendingLock) { _pending.Remove(id); }
                throw new ApiException("请求超时");
            }
            return tcs.Task.Result;
        }

        /// <summary>主动关闭连接。</summary>
        public void Close()
        {
            try
            {
                if (Connected && _ssl != null)
                {
                    SendFrame(0x8, new byte[0]); // close 帧
                }
            }
            catch
            {
                // 忽略关闭过程中的异常
            }
            CloseTransport();
        }

        public void Dispose()
        {
            Close();
        }

        // ---------------- 内部实现 ----------------

        private bool ValidateServerCertificate(object sender, X509Certificate certificate,
            X509Chain chain, SslPolicyErrors sslPolicyErrors)
        {
            string error = _config.ValidateServerCertificate(certificate);
            return error == null;
        }

        private async Task<string> ReadHandshakeStatusAsync()
        {
            // 逐字节读取 HTTP 响应头，直到空行结束
            var buffer = new List<byte>();
            int newlines = 0;
            while (newlines < 2)
            {
                int b = await _ssl.ReadByteAsync(_cts.Token);
                if (b < 0) throw new ApiException("WebSocket 握手失败：连接被关闭");
                buffer.Add((byte)b);
                if (b == '\n') newlines++;
                else if (b != '\r') newlines = 0;
                if (buffer.Count > 16384) throw new ApiException("WebSocket 握手响应异常");
            }
            string header = Encoding.UTF8.GetString(buffer.ToArray());
            int lineEnd = header.IndexOf('\r');
            return lineEnd > 0 ? header.Substring(0, lineEnd) : header;
        }

        private async Task ReceiveLoopAsync()
        {
            try
            {
                var fragments = new MemoryStream();
                bool fragmenting = false;
                while (!_cts.IsCancellationRequested)
                {
                    byte[] header = await ReadExactAsync(2);
                    bool fin = (header[0] & 0x80) != 0;
                    int opcode = header[0] & 0x0F;
                    bool masked = (header[1] & 0x80) != 0;
                    long length = header[1] & 0x7F;
                    if (length == 126)
                    {
                        byte[] ext = await ReadExactAsync(2);
                        // 注意 byte 有符号，必须 & 0xFF 再移位，否则高位被符号扩展
                        length = ((long)(ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                    }
                    else if (length == 127)
                    {
                        byte[] ext = await ReadExactAsync(8);
                        length = 0L;
                        for (int i = 0; i < 8; i++) length = (length << 8) | (ext[i] & 0xFFL);
                    }
                    if (length > int.MaxValue) throw new IOException("帧过大");
                    byte[] mask = masked ? await ReadExactAsync(4) : null;
                    byte[] payload = length > 0 ? await ReadExactAsync((int)length) : new byte[0];
                    if (mask != null)
                    {
                        for (int i = 0; i < payload.Length; i++)
                        {
                            payload[i] = (byte)(payload[i] ^ mask[i % 4]);
                        }
                    }

                    if (opcode == 0x9)
                    {
                        // ping → pong
                        SendFrame(0xA, payload);
                        continue;
                    }
                    if (opcode == 0x8)
                    {
                        break; // 服务端关闭
                    }
                    if (opcode == 0xA)
                    {
                        continue; // pong
                    }
                    if (opcode == 0x0)
                    {
                        // 分片续帧
                        fragments.Write(payload, 0, payload.Length);
                        if (fin)
                        {
                            HandleText(fragments.ToArray());
                            fragments.SetLength(0);
                            fragmenting = false;
                        }
                        continue;
                    }
                    if (opcode == 0x1 || opcode == 0x2)
                    {
                        if (fin && !fragmenting)
                        {
                            HandleText(payload);
                        }
                        else
                        {
                            fragments.SetLength(0);
                            fragments.Write(payload, 0, payload.Length);
                            fragmenting = true;
                        }
                    }
                    // 其余 opcode 忽略
                }
            }
            catch
            {
                // 连接中断：清理并在下方统一通知
            }
            finally
            {
                CloseTransport();
            }
        }

        private void HandleText(byte[] payload)
        {
            Dictionary<string, object> obj;
            try
            {
                obj = Json.Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(payload));
            }
            catch
            {
                return;
            }
            if (obj == null) return;
            object reqValue;
            if (obj.TryGetValue("req", out reqValue) && reqValue != null)
            {
                long req = Convert.ToInt64(reqValue);
                TaskCompletionSource<Dictionary<string, object>> tcs = null;
                lock (_pendingLock)
                {
                    if (_pending.TryGetValue(req, out tcs))
                    {
                        _pending.Remove(req);
                    }
                }
                if (tcs != null)
                {
                    tcs.TrySetResult(obj);
                    return;
                }
            }
            object type;
            if (obj.TryGetValue("type", out type) && type != null && type.ToString() == "message")
            {
                var handler = MessageReceived;
                if (handler != null)
                {
                    try { handler(obj); } catch { }
                }
            }
        }

        private async Task<byte[]> ReadExactAsync(int count)
        {
            var buffer = new byte[count];
            int offset = 0;
            while (offset < count)
            {
                int read = await _ssl.ReadAsync(buffer, offset, count - offset, _cts.Token);
                if (read <= 0) throw new IOException("连接已关闭");
                offset += read;
            }
            return buffer;
        }

        private void SendText(string text)
        {
            SendFrame(0x1, Encoding.UTF8.GetBytes(text));
        }

        /// <summary>发送一个带客户端掩码的数据帧。</summary>
        private void SendFrame(int opcode, byte[] payload)
        {
            lock (_sendLock)
            {
                using (var stream = new MemoryStream())
                {
                    stream.WriteByte((byte)(0x80 | opcode));
                    byte[] mask = RandomBytes(4);
                    int maskBit = 0x80;
                    if (payload.Length < 126)
                    {
                        stream.WriteByte((byte)(maskBit | payload.Length));
                    }
                    else if (payload.Length <= 65535)
                    {
                        stream.WriteByte((byte)(maskBit | 126));
                        stream.WriteByte((byte)(payload.Length >> 8));
                        stream.WriteByte((byte)(payload.Length & 0xFF));
                    }
                    else
                    {
                        stream.WriteByte((byte)(maskBit | 127));
                        // 必须用 long 移位：int 移位 ≥32 位会被按 &31 截断，长度字段会写成乱码
                        long len = payload.Length;
                        for (int i = 7; i >= 0; i--)
                        {
                            stream.WriteByte((byte)((len >> (8 * i)) & 0xFF));
                        }
                    }
                    stream.Write(mask, 0, mask.Length);
                    for (int i = 0; i < payload.Length; i++)
                    {
                        stream.WriteByte((byte)(payload[i] ^ mask[i % 4]));
                    }
                    byte[] frame = stream.ToArray();
                    _ssl.Write(frame, 0, frame.Length);
                    _ssl.Flush();
                }
            }
        }

        private static byte[] RandomBytes(int count)
        {
            var bytes = new byte[count];
            using (var rng = RandomNumberGenerator.Create())
            {
                rng.GetBytes(bytes);
            }
            return bytes;
        }

        private void CloseTransport()
        {
            bool wasConnected = Connected;
            Connected = false;
            if (_cts != null)
            {
                _cts.Cancel();
            }
            try
            {
                if (_ssl != null) _ssl.Dispose();
            }
            catch { }
            try
            {
                if (_tcp != null) _tcp.Close();
            }
            catch { }
            _ssl = null;
            _tcp = null;
            // 使所有未完成请求失败
            List<TaskCompletionSource<Dictionary<string, object>>> waiters;
            lock (_pendingLock)
            {
                waiters = new List<TaskCompletionSource<Dictionary<string, object>>>(_pending.Values);
                _pending.Clear();
            }
            foreach (var tcs in waiters)
            {
                tcs.TrySetException(new ApiException("WebSocket 连接已断开"));
            }
            if (wasConnected)
            {
                var handler = Disconnected;
                if (handler != null)
                {
                    try { handler(); } catch { }
                }
            }
        }
    }

    /// <summary>SslStream 读取单个字节的辅助扩展。</summary>
    internal static class SslStreamExtensions
    {
        public static async Task<int> ReadByteAsync(this SslStream stream, CancellationToken cancellationToken)
        {
            var one = new byte[1];
            int read = await stream.ReadAsync(one, 0, 1, cancellationToken);
            return read <= 0 ? -1 : one[0];
        }
    }
}
