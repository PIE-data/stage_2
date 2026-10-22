package es.ulpgc.piedata.common.http;

/**
 * Error body returned by every endpoint of every service: {@code {"error":"...","message":"..."}}.
 * {@code error} is a stable machine-readable code, {@code message} is for humans.
 */
public record ApiError(String error, String message) {
}
