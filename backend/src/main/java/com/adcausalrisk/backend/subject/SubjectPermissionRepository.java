package com.adcausalrisk.backend.subject;

import com.adcausalrisk.backend.subject.entity.SubjectPermission;
import com.adcausalrisk.backend.subject.entity.SubjectPermission.PermissionType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Data access for the {@code subject_permission} table.
 *
 * <p>The table has a UNIQUE constraint on {@code (subject_id, user_id, permission_type)}, so a user
 * may hold multiple permission types on the same subject - but {@link
 * #findDistinctSubjectIdByUserId} collapses them to a single {@code subjectId}.
 */
@Repository
public interface SubjectPermissionRepository extends JpaRepository<SubjectPermission, Long> {
    @Query(value = "SELECT data_scope FROM sys_user WHERE id=:userId", nativeQuery = true)
    String findDataScope(@Param("userId") Long userId);

    /**
     * Returns every subject the given user has any permission on, with duplicates collapsed. One
     * row in {@code subject_permission} (any permission_type) is enough for the subject to count as
     * visible.
     *
     * <p>Written explicitly with {@code @Query} because the corresponding derived-method name
     * {@code findDistinctSubjectIdByUserId} triggers a Hibernate 6 type-mismatch bug (returns the
     * entity instead of the primitive when the attribute is a simple {@code Long}).
     */
    @Query("SELECT DISTINCT sp.subjectId FROM SubjectPermission sp WHERE sp.userId = :userId")
    List<Long> findDistinctSubjectIdByUserId(@Param("userId") Long userId);

    /**
     * Subjects where the user holds one of the given permission types.
     *
     * <p>Used by the assessment/task lists to build the raw-score window in one query instead of
     * calling {@code findPermissionTypes} once per granted subject.
     */
    @Query(
            """
            SELECT DISTINCT sp.subjectId FROM SubjectPermission sp
            WHERE sp.userId = :userId AND sp.permissionType IN :types
            """)
    List<Long> findDistinctSubjectIdByUserIdAndPermissionTypes(
            @Param("userId") Long userId, @Param("types") Collection<PermissionType> types);

    /**
     * The permission types the given user holds on one subject.
     *
     * <p>Needed by {@code SubjectAccessPolicy.canReadRawScores} to tell {@code VIEW} / {@code
     * MANAGE} (raw scale scores allowed) apart from {@code SUMMARY_READ} (summary only). The old
     * helper collapses every type into a subject id, which is exactly the information this decision
     * needs.
     *
     * <p>An explicit {@code @Query} for the same Hibernate 6 scalar-projection reason as {@link
     * #findDistinctSubjectIdByUserId}.
     */
    @Query(
            """
            SELECT sp.permissionType FROM SubjectPermission sp
            WHERE sp.subjectId = :subjectId AND sp.userId = :userId
            """)
    List<PermissionType> findPermissionTypes(
            @Param("subjectId") Long subjectId, @Param("userId") Long userId);
}
