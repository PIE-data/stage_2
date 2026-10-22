package es.ulpgc.piedata.common.http;

/** Thrown by a handler when the request is invalid; turned into HTTP 400 with an {@link ApiError}. */
public class BadRequestException extends RuntimeException {

    private final String code;

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
