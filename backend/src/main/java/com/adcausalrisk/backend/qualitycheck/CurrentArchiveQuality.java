package com.adcausalrisk.backend.qualitycheck;

import com.adcausalrisk.backend.visit.entity.VisitModelInputs;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Qualifies the saved visit and its latest quality check as one current archive input. */
@Component
public class CurrentArchiveQuality {
    public record Visit(String date, BigDecimal mmse, BigDecimal cdrSb, VisitModelInputs modelInputs) {}

    public record Gate(Long id, String status, Visit visit) {
        public boolean ready() {
            return "PASS".equals(status) || "WARN".equals(status);
        }

        public boolean matchesVisit(JsonNode request) {
            var scores = request.path("features");
            var mmse = scores.path("mmse").path("value");
            var cdrSb = scores.path("cdr_sb").path("value");
            return ready() && visit != null && visit.date().equals(request.path("indexDate").textValue())
                    && mmse.isNumber() && mmse.decimalValue().compareTo(visit.mmse()) == 0
                    && cdrSb.isNumber() && cdrSb.decimalValue().compareTo(visit.cdrSb()) == 0;
        }
    }

    private final JdbcTemplate jdbc;
    private final QualityCheckService qualityChecks;

    public CurrentArchiveQuality(JdbcTemplate jdbc, QualityCheckService qualityChecks) {
        this.jdbc = jdbc;
        this.qualityChecks = qualityChecks;
    }

    public Gate forSubject(long subjectId) {
        record Quality(long id, String status, String version, String digest, String currentVersion, String subjectStatus) {}
        var latest = jdbc.query("""
                SELECT q.id,q.quality_status,q.data_version,q.input_digest,s.data_version AS current_version,s.status
                FROM quality_check q JOIN subject s ON s.id=q.subject_id WHERE s.id=?
                ORDER BY q.checked_at DESC,q.id DESC LIMIT 1
                """, (row, i) -> new Quality(row.getLong(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), row.getString(6)), subjectId).stream().findFirst().orElse(null);
        if (latest == null) return new Gate(null, "NO_CHECK", null);
        if (!"ACTIVE".equals(latest.subjectStatus())) return new Gate(latest.id(), "ARCHIVED", null);
        if (!Objects.equals(latest.version(), latest.currentVersion())
                || !Objects.equals(latest.digest(), qualityChecks.inputDigest(subjectId))) {
            return new Gate(latest.id(), "STALE", null);
        }
        if ("BLOCKED".equals(latest.status())) return new Gate(latest.id(), "BLOCKED", null);
        var visit = jdbc.query("""
                SELECT visit_date,raw_mmse,mmse,raw_cdr_sb,cdr_sb,age_years,education_years,cdr_global,adas_cog13,faq_total
                FROM visit WHERE subject_id=? AND is_current=TRUE ORDER BY visit_date DESC,id DESC LIMIT 1
                """, (row, i) -> new Visit(row.getDate(1).toLocalDate().toString(),
                score(row, 2), score(row, 4), new VisitModelInputs(row.getBigDecimal(6), row.getBigDecimal(7),
                        row.getBigDecimal(8), row.getBigDecimal(9), row.getBigDecimal(10))), subjectId)
                .stream().findFirst().orElse(null);
        return visit != null && visit.mmse() != null && visit.cdrSb() != null
                ? new Gate(latest.id(), latest.status(), visit) : new Gate(latest.id(), "INCOMPLETE", null);
    }

    private static BigDecimal score(ResultSet row, int rawColumn) throws SQLException {
        BigDecimal raw = row.getBigDecimal(rawColumn);
        return raw == null ? row.getBigDecimal(rawColumn + 1) : raw;
    }
}
