namespace LS_Chat
{
    /// <summary>聊天用户。</summary>
    public class User
    {
        public long id;
        public string username;
        public string signature = "";
        public string status = "";

        /// <summary>签名/状态摘要（用于列表第二行显示）。</summary>
        public string Subtitle
        {
            get
            {
                string text = status.Length > 0 ? "[" + status + "]" : "";
                if (signature.Length > 0) text += (text.Length > 0 ? " " : "") + signature;
                return text;
            }
        }

        public override string ToString()
        {
            return username;
        }
    }

    /// <summary>个人资料（签名与状态）。</summary>
    public class UserProfile
    {
        public string signature = "";
        public string status = "";
    }

    /// <summary>聊天消息（group > 0 表示群消息，图片 content 为 Base64）。</summary>
    public class ChatMessage
    {
        public const string TypeText = "text";
        public const string TypeImage = "image";
        public const string TypeVideo = "video";

        public long id;
        public long from;
        public long to;
        public long group;
        public string type;
        public string content;
        public long time;
    }

    /// <summary>群组信息。joinPolicy: approval(需审核) / open(直接加入)；role: owner/admin/member。</summary>
    public class GroupInfo
    {
        public long id;
        public string name;
        public long ownerId;
        public string joinPolicy;
        public bool mutedAll;
        public int memberCount;
        public string role;

        public bool CanManage
        {
            get { return role == "owner" || role == "admin"; }
        }

        public override string ToString()
        {
            return name + " (" + memberCount + "人)";
        }
    }

    /// <summary>群成员。</summary>
    public class GroupMember
    {
        public long id;
        public string username;
        public string role;
        public bool muted;

        public override string ToString()
        {
            string label = role == "owner" ? "群主" : (role == "admin" ? "管理员" : "成员");
            return username + "　[" + label + (muted ? "·已禁言]" : "]");
        }
    }

    /// <summary>入群申请。</summary>
    public class JoinRequest
    {
        public long id;
        public long groupId;
        public string groupName;
        public long userId;
        public string username;

        public override string ToString()
        {
            return username + " 申请加入 " + groupName;
        }
    }

    /// <summary>好友申请（我收到的）。</summary>
    public class FriendRequest
    {
        public long id;
        public long userId;
        public string username;

        public override string ToString()
        {
            return username + "（ID: " + userId + "）请求加你为好友";
        }
    }
}
