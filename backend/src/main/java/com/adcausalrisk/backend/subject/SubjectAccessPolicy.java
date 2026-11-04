package com.adcausalrisk.backend.subject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.subject.entity.SubjectPermission.PermissionType;

/**
 * Subject-level access control. Role-driven visible-range decisions that sit
 * between the Controller (which already ensures the caller is authenticated)
 * and the Repository.
 *
 * <p>The four roles resolve to two visibility tiers:
 * <ul>
 *     <li>{@code ADMIN}, {@code RESEARCHER} - every subject in the table is visible.</li>
 *     <li>{@code DOCTOR}, {@code PATIENT} - only subjects that appear in
 *         {@code subject_permission} for the caller's userId (any permission_type
 *         at all, VIEW / MANAGE / SUMMARY_READ, counts as a grant).</li>
 * </ul>
 *
 * <p>If a user carries multiple roles, the most permissive tier wins. So a
 * {@code RESEARCHER} who also has {@code DOCTOR} still sees every subject.
 */
@Component
public class SubjectAccessPolicy {

    /** Roles that bypass {@code subject_permission} entirely. */
    private static final Set<String> FULL_VIEW_ROLES = Set.of("ADMIN", "RESEARCHER");

    /** Roles allowed to create new subjects. */
    private static final Set<String> CREATOR_ROLES = Set.of("ADMIN", "RESEARCHER");

    /**
     * Roles allowed to receive raw visit scores with no per-subject check.
     *
     * <p>{@code DOCTOR} is deliberately <b>not</b> here: a doctor needs {@code VIEW} or
     * {@code MANAGE} on the specific subject, because {@code SUMMARY_READ} must not be upgraded
     * into a full read.
     */
    private static final Set<String> RAW_SCORE_ROLES = Set.of("ADMIN", "RESEARCHER");

    /** Permission types on a subject that entitle a DOCTOR to raw scale scores. */
    private static final Set<PermissionType> RAW_SCORE_PERMISSION_TYPES =
            Set.of(PermissionType.VIEW, PermissionType.MANAGE);

    private final SubjectPermissionRepository permissionRepository;

    public SubjectAccessPolicy(SubjectPermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    /**
     * Whether the given principal may call {@code POST /api/subjects}.
     * Only {@code ADMIN} and {@code RESEARCHER} may create subjects.
     */
    public boolean canCreate(AppUserPrincipal principal) {
        return principal.roleCodes().stream().anyMatch(CREATOR_ROLES::contains);
    }

    /**
     * Whether the given principal may start an assessment for <i>this</i> subject
     * ({@code POST /api/assessment-tasks}).
     *
     * <p>ADMIN / RESEARCHER may start one for any subject. A DOCTOR may start one only for a
     * subject they hold a grant on - the same {@code VIEW}/{@code MANAGE} rule that already lets
     * them open the drawer and read raw scores. PATIENT is refused outright.
     *
     * <p>This is deliberately narrower than {@link #canCreate}: creating <i>subjects</i> stays
     * restricted to ADMIN / RESEARCHER, while creating an <i>assessment</i> is a clinical action a
     * treating doctor is allowed to take inside the subjects they are authorised for.
     */
    public boolean canRequestAssessment(AppUserPrincipal principal, Long subjectId) {
        if (principal.roleCodes().stream().anyMatch(CREATOR_ROLES::contains)) {
            return true;
        }
        if (!principal.roleCodes().contains("DOCTOR")) {
            return false;
        }
        return canReadRawScores(principal, subjectId);
    }

    /**
     * Whether the given principal bypasses {@code subject_permission} and may see
     * every subject in the table.
     */
    public boolean hasFullVisibility(AppUserPrincipal principal) {
        return principal.roleCodes().stream().anyMatch(FULL_VIEW_ROLES::contains);
    }

    /**
     * Whether the given principal may view (list-item or detail) the single subject.
     *
     * <p>For {@code ADMIN} / {@code RESEARCHER} this is always true. For
     * {@code DOCTOR} / {@code PATIENT} the method returns true only when
     * {@code subject_permission} contains at least one row pairing the subject
     * with the principal's userId.
     *
     * <p>Returns {@code false} (not an exception) when denied - the caller is
     * responsible for translating that into an {@code ACCESS_DENIED} (403)
     * response.
     */
    public boolean canView(AppUserPrincipal principal, Long subjectId) {
        if (hasFullVisibility(principal)) {
            return true;
        }
        List<Long> granted = permissionRepository.findDistinctSubjectIdByUserId(principal.userId());
        return granted.contains(subjectId);
    }

    /**
     * Whether the principal may <b>modify</b> this subject ({@code PATCH /api/subjects/{subjectId}}).
     *
     * <p>ADMIN / RESEARCHER may edit any subject. A DOCTOR needs an explicit {@code MANAGE} grant on
     * that subject - a plain {@code VIEW} grant is not enough, and {@code PATIENT} is refused
     * outright even when another role is present.
     */
    public boolean canManage(AppUserPrincipal principal, Long subjectId) {
        if (principal.roleCodes().contains("PATIENT")) {
            return false;
        }
        if (principal.roleCodes().stream().anyMatch(CREATOR_ROLES::contains)) {
            return true;
        }
        if (!principal.roleCodes().contains("DOCTOR")) {
            return false;
        }
        return permissionRepository.findPermissionTypes(subjectId, principal.userId())
                .contains(PermissionType.MANAGE);
    }

    /**
     * Whether the principal may receive raw visit scores for <i>this</i> subject - the
     * {@code visits} array of {@code SubjectDetail}, which carries {@code mmse}, {@code moca}
     * and {@code cdrSb}.
     *
     * <p>This is a different question from {@link #canView}, which only answers "may this caller
     * see the subject at all". A {@code SUMMARY_READ} grant is for the patient-facing summary and
     * must not be upgraded into a full read, so the decision is per role and, for doctors,
     * per subject:
     *
     * <ul>
     *     <li>{@code ADMIN} / {@code RESEARCHER}: always allowed.</li>
     *     <li>{@code DOCTOR}: allowed only when the doctor holds {@code VIEW} or {@code MANAGE}
     *         on this subject. A doctor holding only {@code SUMMARY_READ} is refused, which is
     *         the privilege-escalation this method exists to prevent.</li>
     *     <li>{@code PATIENT}: refused, always.</li>
     * </ul>
     *
     * <p>{@code PATIENT} is checked <b>first</b> and wins over every other role: an account that
     * carries PATIENT alongside RESEARCHER or ADMIN still receives no raw scale scores. Every
     * other multi-role combination takes the most permissive applicable branch, so DOCTOR
     * combined with PATIENT still needs VIEW or MANAGE on the subject.
     */
    public boolean canReadRawScores(AppUserPrincipal principal, Long subjectId) {
        // Checked before anything else on purpose: a patient-facing response must never carry
        // raw scale scores, even when the same account also holds a clinician or research role.
        if (principal.roleCodes().contains("PATIENT")) {
            return false;
        }
        if (principal.roleCodes().stream().anyMatch(RAW_SCORE_ROLES::contains)) {
            return true;
        }
        if (!principal.roleCodes().contains("DOCTOR")) {
            return false;
        }
        return permissionRepository.findPermissionTypes(subjectId, principal.userId()).stream()
                .anyMatch(RAW_SCORE_PERMISSION_TYPES::contains);
    }

    /**
     * Filters a raw list of subjects down to those the principal may see.
     *
     * <p>{@code ADMIN} / {@code RESEARCHER} receive the input list unchanged.
     * Other roles receive only subjects whose {@code id} appears in
     * {@code subject_permission} for their userId.
     *
     * <p><b>Important:</b> callers must filter <i>before</i> paging. Paging on
     * the raw table then filtering would produce wrong {@code total} values
     * and pages that return fewer than {@code pageSize} items.
     */
    public List<Subject> filterVisible(AppUserPrincipal principal, List<Subject> subjects) {
        if (hasFullVisibility(principal)) {
            return subjects;
        }
        Set<Long> granted = new HashSet<>(permissionRepository.findDistinctSubjectIdByUserId(principal.userId()));
        return subjects.stream()
                .filter(s -> granted.contains(s.getId()))
                .toList();
    }

    /**
     * Returns the set of subject IDs the principal is allowed to see -
     * <b>only meaningful when {@link #hasFullVisibility} returns {@code false}</b>.
     *
     * <p>When {@code hasFullVisibility} is {@code true}, callers must ignore this
     * result and query the whole table instead. When {@code false}, the returned
     * set (which may itself be empty if the caller has zero grants) is the exact
     * pagination window that must be used.
     *
     * <p>This deliberately avoids the ambiguity of the older design where an empty
     * set meant two opposite things ("see everything" vs. "see nothing").
     */
    public Set<Long> grantedSubjectIds(AppUserPrincipal principal) {
        return new HashSet<>(permissionRepository.findDistinctSubjectIdByUserId(principal.userId()));
    }
}