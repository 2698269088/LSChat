package top.mcocet.server;

/**
 * 消息类型与内容长度校验，HTTP 与 WebSocket 两种通信模式共用。
 * 返回 null 表示合法，否则返回错误提示文案。
 */
final class MessageContent {

    static final int MAX_TEXT_LENGTH = 4000;
    static final int MAX_IMAGE_BASE64_LENGTH = 8_000_000;
    static final int MAX_VIDEO_BASE64_LENGTH = 60_000_000;
    static final int MAX_AVATAR_BASE64_LENGTH = 2_000_000;
    static final int MAX_SIGNATURE_LENGTH = 30;
    static final int MAX_STATUS_LENGTH = 20;

    private MessageContent() {
    }

    /** 校验消息类型与内容长度，返回 null 或错误信息。 */
    static String validate(String type, String content) {
        if (ChatMessage.TYPE_TEXT.equals(type)) {
            if (content.isEmpty() || content.length() > MAX_TEXT_LENGTH) {
                return "文字内容为空或过长";
            }
        } else if (ChatMessage.TYPE_IMAGE.equals(type)) {
            if (content.isEmpty() || content.length() > MAX_IMAGE_BASE64_LENGTH) {
                return "图片为空或过大";
            }
        } else if (ChatMessage.TYPE_VIDEO.equals(type)) {
            if (content.isEmpty() || content.length() > MAX_VIDEO_BASE64_LENGTH) {
                return "视频为空或过大（最大约 40MB）";
            }
        } else {
            return "不支持的消息类型";
        }
        return null;
    }

    /** 校验头像：Base64 可解码、长度受限、魔数匹配 JPEG(FFD8) 或 PNG(89504E47)。返回 null 或错误信息。 */
    static String validateAvatar(String content) {
        if (content == null || content.isEmpty() || content.length() > MAX_AVATAR_BASE64_LENGTH) {
            return "头像为空、过大或格式不支持（仅 JPEG/PNG）";
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            return "头像 Base64 无法解码";
        }
        if (bytes.length < 4) return "头像数据过短";
        boolean jpeg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
        boolean png = (bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47;
        if (!jpeg && !png) return "头像格式不支持（仅 JPEG/PNG）";
        return null;
    }
}
