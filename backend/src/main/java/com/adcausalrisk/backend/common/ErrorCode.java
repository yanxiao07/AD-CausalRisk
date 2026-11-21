package com.adcausalrisk.backend.common;

import org.springframework.http.HttpStatus;

/**
 * Stable error identifiers.
 *
 * <p>The enum <em>name</em> is the API-visible {@code errorCode} string, so renaming a constant is
 * a breaking contract change.
 *
 * <p>{@code code} mirrors the HTTP status: the status is the coarse class a caller can branch on,
 * {@code errorCode} is the specific identifier, and the HTTP response uses the same status so a
 * refusal is never disguised as HTTP 200.
 *
 * <p>The frozen target contract declares 400/401/403 for the operations in scope. 404, 405, 415 and
 * 500 are added because the framework can reject or fail a request before any controller runs, and
 * an unformatted error body would break the "every response is an Envelope" rule. {@link
 * #NOT_FOUND} covers <em>unrouted</em> requests only; a missing business resource (for example an
 * unknown subject) uses a module-specific code with HTTP 400, because the frozen contract declares
 * no 404 for those operations.
 */
public enum ErrorCode {

    /** Request parameters or body failed validation. */
    VALIDATION_ERROR(400, HttpStatus.BAD_REQUEST, "请求参数无效"),

    /** Body could not be read as JSON, or carried properties the contract does not define. */
    MALFORMED_JSON(400, HttpStatus.BAD_REQUEST, "请求体格式无效或包含契约未定义的字段"),

    /** Login refused. The message is identical for an unknown user and a wrong password. */
    AUTH_INVALID_CREDENTIALS(401, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),

    /** No session, or the session is no longer valid. */
    UNAUTHENTICATED(401, HttpStatus.UNAUTHORIZED, "未登录或会话已失效"),

    /** Authenticated but not allowed: wrong role, or outside the object-level scope. */
    ACCESS_DENIED(403, HttpStatus.FORBIDDEN, "无权访问该资源"),

    /** No route matches the request. Not used for missing business resources. */
    NOT_FOUND(404, HttpStatus.NOT_FOUND, "请求的资源不存在"),

    METHOD_NOT_ALLOWED(405, HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),
    TASK_STATE_CONFLICT(409, HttpStatus.CONFLICT, "当前任务状态不支持此操作"),

    UNSUPPORTED_MEDIA_TYPE(415, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的请求 Content-Type"),
    RESEARCH_UNAVAILABLE(503, HttpStatus.SERVICE_UNAVAILABLE, "研究评估服务暂不可用"),

    /** Unexpected server failure. The detail stays in the log and never reaches the client. */
    INTERNAL_ERROR(500, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final int code;

    private final HttpStatus status;

    private final String defaultMessage;

    ErrorCode(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
