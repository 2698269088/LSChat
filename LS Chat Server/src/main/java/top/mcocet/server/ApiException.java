package top.mcocet.server;

/** 业务异常，code 会原样返回给客户端。 */
public class ApiException extends RuntimeException {

    private final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
