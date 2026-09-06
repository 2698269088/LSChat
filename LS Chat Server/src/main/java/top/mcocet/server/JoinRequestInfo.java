package top.mcocet.server;

import org.json.JSONObject;

/** 入群申请。 */
public record JoinRequestInfo(long id, long groupId, String groupName, long userId, String username) {

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("groupId", groupId);
        object.put("groupName", groupName);
        object.put("userId", userId);
        object.put("username", username);
        return object;
    }
}
