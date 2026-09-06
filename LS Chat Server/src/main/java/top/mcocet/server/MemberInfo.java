package top.mcocet.server;

import org.json.JSONObject;

/** 群成员（含角色与禁言状态）。role: owner / admin / member。 */
public record MemberInfo(long userId, String username, String role, boolean muted) {

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("id", userId);
        object.put("username", username);
        object.put("role", role);
        object.put("muted", muted);
        return object;
    }
}
