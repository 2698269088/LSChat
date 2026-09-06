package top.mcocet.server;

import org.json.JSONObject;

/** 好友申请（我收到的）。 */
public record FriendRequestInfo(long id, long userId, String username) {

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("userId", userId);
        object.put("username", username);
        return object;
    }
}
