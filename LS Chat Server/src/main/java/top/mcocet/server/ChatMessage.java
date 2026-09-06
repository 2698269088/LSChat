package top.mcocet.server;

import org.json.JSONObject;

public record ChatMessage(long id, long from, long to, long groupId, String type, String content, long time) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_VIDEO = "video";

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("from", from);
        object.put("to", to);
        object.put("group", groupId);
        object.put("type", type);
        object.put("content", content);
        object.put("time", time);
        return object;
    }
}
