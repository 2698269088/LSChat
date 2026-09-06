package top.mcocet.server;

import org.json.JSONObject;

public record User(long id, String username, String signature, String status) {

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("username", username);
        object.put("signature", signature);
        object.put("status", status);
        return object;
    }
}
