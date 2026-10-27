package es.ulpgc.piedata.common.http;

/**
 * Thrown by a handler to answer with a specific HTTP status and an {@link ApiError} body,
 * e.g. {@code throw new ApiException(404, "not_ingested", "book 42 is not in the datalake")}.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
