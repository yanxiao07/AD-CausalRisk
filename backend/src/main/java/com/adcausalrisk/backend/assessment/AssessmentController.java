package com.adcausalrisk.backend.assessment;

import com.adcausalrisk.backend.agent.AgentResult;
import com.adcausalrisk.backend.assessment.entity.AssessmentTask;
import com.adcausalrisk.backend.assessment.entity.RiskAssessment;
import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * HTTP surface of the assessment task:
 *
 * <ul>
 *   <li>{@code POST /api/assessment-tasks} - idempotent create, runs the Python workflow
 *       synchronously and returns the final task state.
 *   <li>{@code GET /api/assessment-tasks/{taskId}} - the persisted task plus its stages.
 *   <li>{@code GET /api/assessments/{assessmentId}} - the stored result.
 * </ul>
 *
 * <p>Every decision (role, object access, idempotency) is taken in {@link AssessmentService}; this
 * class only bridges HTTP to it, as the other controllers do.
 */
@RestController
public class AssessmentController {

    private final AssessmentService assessmentService;
    private final com.adcausalrisk.backend.common.BusinessQueries queries;

    public AssessmentController(
            AssessmentService assessmentService,
            com.adcausalrisk.backend.common.BusinessQueries queries) {
        this.assessmentService = assessmentService;
        this.queries = queries;
    }

    /** Body of {@code AssessmentCreate}; the contract forbids additional properties. */
    public record AssessmentCreateRequest(
            @NotNull @Min(1) Long subjectId,
            @NotBlank String modelVersion,
            @NotNull @Min(6) @Max(60) Integer predictionWindowMonths) {}

    /** {@code AssessmentTask.stages[]}; durations are the measured workflow times. */
    public record StageView(
            String stage, String status, double durationMs, Map<String, Object> summary) {}

    /** {@code AssessmentTask} in contracts/java-business.target.openapi.json. */
    public record AssessmentTaskView(
            long taskId,
            long subjectId,
            String status,
            String runId,
            Long assessmentId,
            String errorCode,
            String message,
            Instant createdAt,
            Instant updatedAt,
            List<StageView> stages) {}

    /** {@code Assessment} in contracts/java-business.target.openapi.json. */
    public record AssessmentView(
            long assessmentId,
            long subjectId,
            long taskId,
            AgentResult agentResult,
            String reviewStatus,
            Instant createdAt) {}

    @PostMapping("/api/assessment-tasks")
    public Envelope<AssessmentTaskView> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AssessmentCreateRequest request,
            Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        AssessmentService.Outcome outcome =
                assessmentService.create(
                        request.subjectId(),
                        request.modelVersion(),
                        request.predictionWindowMonths(),
                        idempotencyKey,
                        principal);
        return Envelope.success(taskView(outcome));
    }

    @GetMapping("/api/assessment-tasks/{taskId}")
    public Envelope<AssessmentTaskView> getTask(
            @PathVariable Long taskId, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(taskView(assessmentService.findTask(taskId, principal)));
    }

    @PostMapping("/api/assessment-tasks/submit")
    public Envelope<AssessmentTaskView> submit(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AssessmentCreateRequest request,
            Authentication authentication) {
        return Envelope.success(
                taskView(
                        assessmentService.submit(
                                request.subjectId(),
                                request.modelVersion(),
                                request.predictionWindowMonths(),
                                key,
                                (AppUserPrincipal) authentication.getPrincipal())));
    }

    @PostMapping("/api/assessment-tasks/{taskId}/cancel")
    public Envelope<AssessmentTaskView> cancel(
            @PathVariable long taskId, Authentication authentication) {
        return Envelope.success(
                taskView(
                        assessmentService.cancel(
                                taskId, (AppUserPrincipal) authentication.getPrincipal())));
    }

    /**
     * {@code GET /api/assessment-tasks} - paged task history for the review queue and the batch-run
     * follow-up. Returns summaries only (no stage details), newest first.
     */
    @GetMapping("/api/assessment-tasks")
    public Envelope<?> listTasks(
            @RequestParam java.util.Map<String, String> filters, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(queries.list("tasks", filters, principal));
    }

    /**
     * {@code GET /api/assessments} - paged result summaries, newest first.
     *
     * <p>This is what the review queue and the report list load instead of asking the user to type
     * an id. The payload excludes {@code agentResult} so a page stays small.
     */
    @GetMapping("/api/assessments")
    public Envelope<?> listAssessments(
            @RequestParam java.util.Map<String, String> filters, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(queries.list("assessments", filters, principal));
    }

    @GetMapping("/api/assessments/{assessmentId}")
    public Envelope<AssessmentView> getAssessment(
            @PathVariable Long assessmentId, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        AssessmentService.Outcome outcome =
                assessmentService.findAssessment(assessmentId, principal);
        RiskAssessment assessment = outcome.assessment();
        return Envelope.success(
                new AssessmentView(
                        assessment.getId(),
                        assessment.getSubjectId(),
                        assessment.getTaskId(),
                        outcome.result(),
                        assessment.getReviewStatus().name(),
                        assessment.getCreatedAt()));
    }

    private static AssessmentTaskView taskView(AssessmentService.Outcome outcome) {
        AssessmentTask task = outcome.task();
        RiskAssessment assessment = outcome.assessment();
        List<StageView> stages =
                outcome.result() == null
                        ? List.of()
                        : outcome.result().stages().stream()
                                .map(
                                        stage ->
                                                new StageView(
                                                        stage.stage().name(),
                                                        stage.status().name(),
                                                        stage.durationMs(),
                                                        stage.summary()))
                                .toList();
        return new AssessmentTaskView(
                task.getId(),
                task.getSubjectId(),
                task.getStatus().name(),
                task.getRunId(),
                assessment == null ? null : assessment.getId(),
                outcome.errorCode(),
                outcome.errorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                stages);
    }
}
