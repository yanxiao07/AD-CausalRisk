package com.adcausalrisk.backend.agent;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Synchronous client for the Python agent service.
 *
 * <p>Deliberately no retries: a timed-out POST may or may not have executed, and the service's own
 * contract forbids minting a second runId to work around it. Timeouts surface as
 * {@code AGENT_TIMEOUT} and the runId stays with the caller for reconciliation through {@link #getRun}.
 *
 * <p>Every failure is a {@link BusinessException} whose message starts with a machine code, which
 * {@link #errorCodeOf(Throwable)} extracts for the assessment_task row.
 */
public class RestAgentClient {

    public static final String CODE_TIMEOUT = "AGENT_TIMEOUT";
    public static final String CODE_UNAVAILABLE = "AGENT_UNAVAILABLE";
    public static final String CODE_VALIDATION = "AGENT_VALIDATION";
    public static final String CODE_RUN_CONFLICT = "AGENT_RUN_CONFLICT";
    public static final String CODE_RUN_NOT_FOUND = "AGENT_RUN_NOT_FOUND";
    public static final String CODE_PAYLOAD_TOO_LARGE = "AGENT_PAYLOAD_TOO_LARGE";
    public static final String CODE_PROTOCOL_ERROR = "AGENT_PROTOCOL_ERROR";
    public static final String CODE_CAPACITY = "AGENT_CAPACITY";
    public static final String CODE_FAILED = "AGENT_FAILED";

    private static final String RUNS_PATH = "/api/agent/v1/runs";
    private static final int MAX_DETAIL_LENGTH = 300;

    /**
     * Jackson 的 ObjectMapper 是线程安全的，这里共用一个静态实例即可；
     * 不改成构造器注入是为了让本类保持独立（AgentClientConfig 只负责注入 RestClient）。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;

    public RestAgentClient(RestClient agentRestClient) {
        this.restClient = agentRestClient;
    }

    /** {@code POST /api/agent/v1/runs}. Blocks until the four-stage workflow finishes. */
    public AgentResult createRun(AgentRequest request) {
        // Python 端要求"带 Content-Length 的 JSON 请求"，所以先自己序列化成 byte[]，
        // 把长度显式写进请求头，而不是让底层转换器选择 chunked 传输。
        byte[] bodyBytes;
        try {
            bodyBytes = OBJECT_MAPPER.writeValueAsBytes(request);
        } catch (JsonProcessingException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    CODE_PROTOCOL_ERROR + ": 请求序列化失败 " + detail(failure));
        }

        try {
            AgentResult result = restClient.post()
                    .uri(RUNS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentLength(bodyBytes.length)
                    .body(bodyBytes)
                    .retrieve()
                    .body(AgentResult.class);

            AgentResultValidator.validate(request, result);
            return result;
        } catch (ResourceAccessException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, CODE_TIMEOUT + ": " + detail(failure));
        } catch (RestClientResponseException failure) {
            throw mapResponse(failure, false);
        } catch (RestClientException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    AgentResultValidator.CODE + ": " + detail(failure));
        }
    }

    /**
     * {@code GET /api/agent/v1/runs/{runId}}. Returns a completed result from the service's cache.
     * A 404 means "not finished or not in the cache", never "no execution happened".
     */
    public AgentResult getRun(String runId) {
        try {
            AgentResult result = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(RUNS_PATH).pathSegment(runId).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(AgentResult.class);
            AgentResultValidator.validate(result);
            return result;
        } catch (ResourceAccessException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, CODE_TIMEOUT + ": " + detail(failure));
        } catch (RestClientResponseException failure) {
            throw mapResponse(failure, true);
        } catch (RestClientException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    AgentResultValidator.CODE + ": " + detail(failure));
        }
    }

    /** Machine-readable code of a failure produced by this client, for persistence/telemetry. */
    public static String errorCodeOf(Throwable failure) {
        if (failure instanceof BusinessException business && business.getMessage() != null) {
            String message = business.getMessage();
            int colon = message.indexOf(':');
            if (colon > 0) {
                String prefix = message.substring(0, colon).trim();
                if (prefix.matches("[A-Z][A-Z0-9_]{2,40}")) {
                    return prefix;
                }
            }
        }
        return CODE_FAILED;
    }

    private BusinessException mapResponse(RestClientResponseException failure, boolean readOperation) {
        HttpStatusCode status = failure.getStatusCode();
        String code = switch (status.value()) {
            case 400 -> CODE_VALIDATION;
            case 404 -> readOperation ? CODE_RUN_NOT_FOUND : CODE_FAILED;
            case 408 -> CODE_TIMEOUT;
            case 409 -> CODE_RUN_CONFLICT;
            case 413 -> CODE_PAYLOAD_TOO_LARGE;
            case 415 -> CODE_PROTOCOL_ERROR;
            case 500 -> CODE_FAILED;
            case 503 -> CODE_CAPACITY;
            default -> CODE_FAILED;
        };
        return new BusinessException(ErrorCode.INTERNAL_ERROR,
                code + ": HTTP " + status.value() + " " + detail(failure));
    }

    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() <= MAX_DETAIL_LENGTH ? single : single.substring(0, MAX_DETAIL_LENGTH) + "…";
    }
}