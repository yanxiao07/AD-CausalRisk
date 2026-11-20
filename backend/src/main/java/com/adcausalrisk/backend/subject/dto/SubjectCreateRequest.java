package com.adcausalrisk.backend.subject.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/subjects}. Maps the frozen contract schema
 * {@code SubjectCreate} exactly - no extra fields, no fewer.
 *
 * <p>{@code status} is optional in the contract; {@code SubjectService} defaults
 * it to {@code ACTIVE} when absent.
 */
public record SubjectCreateRequest(

        @NotBlank(message = "subjectCode 不能为空")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,49}$",
                message = "档案编号最长 50 位，只能包含字母、数字或短横线，且须以字母或数字开始")
        String subjectCode,

        @NotBlank(message = "alias 不能为空")
        String alias,

        String status,
        @Pattern(regexp = "^CARE$", message = "无效的档案用途") String archiveType
) {
}
