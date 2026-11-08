package com.adcausalrisk.backend.qualitycheck;

import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Persisted input eligibility; human approval does not repair missing or invalid source data. */
@Service
public class QualityCheckService {
    static final String RULE_VERSION = "qc-rules-demo-v2";
    private final JdbcTemplate jdbc;
    private final SubjectRepository subjects;
    private final SubjectAccessPolicy policy;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public QualityCheckService(
            JdbcTemplate jdbc,
            SubjectRepository subjects,
            SubjectAccessPolicy policy,
            Clock clock) {
        this.jdbc = jdbc;
        this.subjects = subjects;
        this.policy = policy;
        this.clock = clock;
    }

    public record QualityCheckResult(
            long id,
            long subjectId,
            String qualityStatus,
            List<String> issues,
            Instant checkedAt,
            String ruleVersion) {}

    @Transactional
    public QualityCheckResult check(Long id, AppUserPrincipal principal) {
        if (!policy.canReadRawScores(principal, id))
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权执行质量检查");
        var subject =
                subjects.findById(id)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在"));
        var visits =
                jdbc.queryForList(
                        "SELECT id,visit_date,mmse,moca,cdr_sb,raw_mmse,raw_moca,raw_cdr_sb FROM"
                                + " visit WHERE subject_id=? ORDER BY visit_date,id",
                        id);
        var features =
                jdbc.queryForList(
                        "SELECT id,feature_name,feature_value,recorded_at,unit FROM subject_feature"
                                + " WHERE subject_id=? ORDER BY recorded_at,id",
                        id);
        List<String> issues = new ArrayList<>();
        boolean blocked = visits.isEmpty(), cognition = false;
        int observed = 0, missing = 0;
        LocalDate today = LocalDate.now(clock);
        for (var visit : visits) {
            boolean latest = visit == visits.get(visits.size() - 1);
            if (((java.sql.Date) visit.get("visit_date")).toLocalDate().isAfter(today)) {
                blocked = true;
                issues.add("访视日期晚于业务日");
            }
            for (String field : List.of("mmse", "moca", "cdr_sb")) {
                Object value =
                        visit.get("raw_" + field) != null
                                ? visit.get("raw_" + field)
                                : visit.get(field);
                if (value == null) {
                    missing++;
                    continue;
                }
                observed++;
                try {
                    BigDecimal number = new BigDecimal(value.toString());
                    if (number.signum() < 0
                            || number.compareTo(
                                            BigDecimal.valueOf(field.equals("cdr_sb") ? 18 : 30))
                                    > 0) {
                        blocked = true;
                        issues.add(field + " 原始值超出有效范围");
                    } else if (latest) cognition = true;
                } catch (NumberFormatException invalid) {
                    blocked = true;
                    issues.add(field + " 原始值不可解析");
                }
            }
        }
        if (!cognition) {
            blocked = true;
            issues.add("最近访视没有可用的核心认知观测，不以前次资料补齐");
        }
        if (!visits.isEmpty()) {
            Object indexDate = visits.get(visits.size() - 1).get("visit_date");
            if (visits.stream().filter(v -> Objects.equals(v.get("visit_date"), indexDate)).count()
                    > 1) {
                blocked = true;
                issues.add("最新日期存在多个访视，索引不唯一");
            }
            var latest = visits.get(visits.size() - 1);
            if (List.of("mmse", "moca", "cdr_sb").stream()
                    .anyMatch(f -> latest.get("raw_" + f) == null && latest.get(f) == null))
                issues.add("最近访视的部分认知项缺失，不补零");
        }
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        Map<String, Set<BigDecimal>> dayValues = new HashMap<>();
        for (var feature : features) {
            String name = feature.get("feature_name").toString();
            if (feature.get("feature_value") == null) {
                missing++;
                continue;
            }
            observed++;
            sourceCounts.merge(name, 1, Integer::sum);
            BigDecimal number = new BigDecimal(feature.get("feature_value").toString());
            boolean apoe = name.equals("APOE_E4_COUNT");
            if (number.signum() < 0
                    || number.compareTo(BigDecimal.valueOf(apoe ? 2 : 1)) > 0
                    || (apoe && number.stripTrailingZeros().scale() > 0)) {
                blocked = true;
                issues.add(name + " 原始值超出有效范围");
            }
            if (!Objects.equals(feature.get("unit"), apoe ? "count" : "normalized")) {
                blocked = true;
                issues.add(name + " 单位不匹配");
            }
            LocalDate date = ((java.sql.Date) feature.get("recorded_at")).toLocalDate();
            if (date.isAfter(today)) {
                blocked = true;
                issues.add(name + " 采集日期晚于业务日");
            }
            if (!visits.isEmpty()
                    && date.isAfter(
                            ((java.sql.Date) visits.get(visits.size() - 1).get("visit_date"))
                                    .toLocalDate())) issues.add(name + " 晚于索引访视，不参与当前评分");
            var values = dayValues.computeIfAbsent(name + date, k -> new HashSet<>());
            values.add(number.stripTrailingZeros());
            if (values.size() > 1) {
                blocked = true;
                issues.add(name + " 同日观测冲突");
            }
        }
        for (String source : List.of("APOE_E4_COUNT", "MRI_HIPPOCAMPUS_L", "MRI_HIPPOCAMPUS_R"))
            if (!sourceCounts.containsKey(source)) issues.add(source + " 缺失");
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM qc_audit_record WHERE subject_id=? AND"
                                + " status='PENDING'",
                        Integer.class,
                        id)
                > 0) issues.add("存在待人工复核的数据记录");
        String status = blocked ? "BLOCKED" : issues.isEmpty() ? "PASS" : "WARN";
        var coverage = new LinkedHashMap<String, Object>();
        coverage.put("visits", visits.size());
        coverage.put("features", features.size());
        coverage.put("observed", observed);
        coverage.put("missing", missing);
        coverage.put("sources", sourceCounts);
        if (!visits.isEmpty()) {
            coverage.put("firstVisit", visits.get(0).get("visit_date").toString());
            coverage.put("lastVisit", visits.get(visits.size() - 1).get("visit_date").toString());
        }
        Instant now = Instant.now(clock);
        try {
            String input = json.writeValueAsString(List.of(visits, features));
            String digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(input.getBytes(StandardCharsets.UTF_8)));
            var keys = new GeneratedKeyHolder();
            String issuesJson = json.writeValueAsString(issues.stream().distinct().toList()),
                    coverageJson = json.writeValueAsString(coverage);
            jdbc.update(
                    connection -> {
                        var statement =
                                connection.prepareStatement(
                                        "INSERT INTO"
                                            + " quality_check(subject_id,quality_status,issues,checked_at,rule_version,checked_by,data_version,input_digest,coverage_json)"
                                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                                        new String[] {"id"});
                        statement.setLong(1, id);
                        statement.setString(2, status);
                        statement.setString(3, issuesJson);
                        statement.setTimestamp(4, java.sql.Timestamp.from(now));
                        statement.setString(5, RULE_VERSION);
                        statement.setLong(6, principal.userId());
                        statement.setString(7, subject.getDataVersion());
                        statement.setString(8, digest);
                        statement.setString(9, coverageJson);
                        return statement;
                    },
                    keys);
            return new QualityCheckResult(
                    Objects.requireNonNull(keys.getKey()).longValue(),
                    id,
                    status,
                    issues.stream().distinct().toList(),
                    now,
                    RULE_VERSION);
        } catch (Exception failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "质量检查保存失败");
        }
    }
}
