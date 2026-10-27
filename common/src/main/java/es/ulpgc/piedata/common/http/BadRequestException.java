package es.ulpgc.piedata.common.http;

/** Thrown by a handler when the request is invalid; turned into HTTP 400 with an {@link ApiError}. */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(400, code, message);
    }
}
