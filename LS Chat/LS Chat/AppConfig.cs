using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Web.Script.Serialization;

namespace LS_Chat
{
    /// <summary>
    /// 本地配置持久化（JSON 文件，存于 %AppData%\LSChat\config.json）：
    /// 服务器地址、通信协议、登录令牌、当前用户、TOFU 证书指纹、已读消息位置。
    /// </summary>
    public class AppConfig
    {
        public string ServerHost = "";
        public int ServerPort = 18443;
        /// <summary>通信协议：websocket（默认，WSS 长连接实时推送）/ http（HTTPS 轮询）。空值视为 websocket。</summary>
        public string Protocol = "";
        public string Token = "";
        public long UserId = -1;
        public string UserName = "";
        public string PinnedCertSha256 = "";
        public long LastSeenMessageId = 0;

        private static readonly string DirPath =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "LSChat");

        private static readonly string FilePath = Path.Combine(DirPath, "config.json");

        public static AppConfig Load()
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    string json = File.ReadAllText(FilePath);
                    var config = new JavaScriptSerializer().Deserialize<AppConfig>(json);
                    if (config != null) return config;
                }
            }
            catch
            {
                // 配置损坏时回退默认
            }
            return new AppConfig();
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(DirPath);
                File.WriteAllText(FilePath, new JavaScriptSerializer().Serialize(this));
            }
            catch
            {
                // 保存失败不影响运行
            }
        }

        /// <summary>
        /// TOFU 校验服务器证书：首次连接时信任并记录 SHA-256 指纹，
        /// 之后每次连接校验指纹一致，防止中间人攻击。
        /// 返回 null 表示通过，否则返回拒绝原因。
        /// </summary>
        public string ValidateServerCertificate(X509Certificate certificate)
        {
            string hash = ((X509Certificate2)certificate).GetCertHashString(HashAlgorithmName.SHA256);
            if (string.IsNullOrEmpty(PinnedCertSha256))
            {
                PinnedCertSha256 = hash;
                Save();
                return null;
            }
            if (!string.Equals(PinnedCertSha256, hash, StringComparison.OrdinalIgnoreCase))
            {
                return "服务器证书与首次连接时不一致，连接已被阻止";
            }
            return null;
        }
    }
}
