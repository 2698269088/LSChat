package top.mcocet.server;

import org.json.JSONObject;

/** 个人资料：签名与状态。 */
public record UserProfile(String signature, String status) {

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("signature", signature);
        object.put("status", status);
        return object;
    }
}
