package top.mcocet.lschat.data

import org.json.JSONObject

data class User(
    val id: Long,
    val username: String,
    val signature: String = "",
    val status: String = ""
) {
    /** 签名/状态摘要（用于列表第二行显示）。 */
    val subtitle: String
        get() = listOf(
            status.takeIf { it.isNotEmpty() }?.let { "[$it]" },
            signature.takeIf { it.isNotEmpty() }
        ).filterNotNull().joinToString(" ")

    companion object {
        fun fromJson(json: JSONObject) = User(
            id = json.getLong("id"),
            username = json.getString("username"),
            signature = json.optString("signature", ""),
            status = json.optString("status", "")
        )
    }
}

data class Message(
    val id: Long,
    val from: Long,
    val to: Long,
    val type: String,
    val content: String,
    val time: Long,
    val group: Long = 0 // >0 表示群消息
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"

        fun fromJson(json: JSONObject) = Message(
            id = json.getLong("id"),
            from = json.getLong("from"),
            to = json.optLong("to"),
            type = json.getString("type"),
            content = json.getString("content"),
            time = json.optLong("time"),
            group = json.optLong("group", 0)
        )
    }
}

/** 群组。joinPolicy: approval(需审核)/open(直接加入)；role: owner/admin/member，null=非成员。 */
data class GroupInfo(
    val id: Long,
    val name: String,
    val ownerId: Long,
    val joinPolicy: String,
    val mutedAll: Boolean,
    val memberCount: Int,
    val role: String?,
    val pending: Boolean = false // 已提交入群申请、等待审核（搜索接口返回）
) {
    val canManage: Boolean get() = role == "owner" || role == "admin"

    companion object {
        fun fromJson(json: JSONObject) = GroupInfo(
            id = json.getLong("id"),
            name = json.getString("name"),
            ownerId = json.optLong("ownerId"),
            joinPolicy = json.optString("joinPolicy", "approval"),
            mutedAll = json.optBoolean("mutedAll", false),
            memberCount = json.optInt("memberCount", 0),
            role = if (json.isNull("role")) null
            else json.optString("role").takeUnless { it == "null" },
            pending = json.optBoolean("pending", false)
        )
    }
}

/** 群成员。role: owner/admin/member。 */
data class GroupMember(
    val id: Long,
    val username: String,
    val role: String,
    val muted: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject) = GroupMember(
            id = json.getLong("id"),
            username = json.getString("username"),
            role = json.getString("role"),
            muted = json.optBoolean("muted", false)
        )
    }
}

/** 入群申请。 */
data class JoinRequest(
    val id: Long,
    val groupId: Long,
    val groupName: String,
    val userId: Long,
    val username: String
) {
    companion object {
        fun fromJson(json: JSONObject) = JoinRequest(
            id = json.getLong("id"),
            groupId = json.getLong("groupId"),
            groupName = json.getString("groupName"),
            userId = json.getLong("userId"),
            username = json.getString("username")
        )
    }
}

/** 好友申请（我收到的）。 */
data class FriendRequest(
    val id: Long,
    val userId: Long,
    val username: String
) {
    companion object {
        fun fromJson(json: JSONObject) = FriendRequest(
            id = json.getLong("id"),
            userId = json.getLong("userId"),
            username = json.getString("username")
        )
    }
}
