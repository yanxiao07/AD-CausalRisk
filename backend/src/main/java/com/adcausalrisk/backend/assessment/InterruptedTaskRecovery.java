package com.adcausalrisk.backend.assessment;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** This local application owns its task database. Never silently recompute an interrupted run. */
@Component
public class InterruptedTaskRecovery {
    private final JdbcTemplate jdbc;

    public InterruptedTaskRecovery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var row :
                jdbc.queryForList(
                        "SELECT id,agent_result_json FROM risk_assessment WHERE risk_level IS"
                                + " NULL")) {
            try {
                var node = json.readTree(row.get("agent_result_json").toString());
                String level = node.path("riskLevel").asText(null);
                if (level != null)
                    jdbc.update(
                            "UPDATE risk_assessment SET risk_level=?,risk_score=? WHERE id=?",
                            level,
                            node.path("riskScore").isNumber()
                                    ? node.path("riskScore").decimalValue()
                                    : null,
                            row.get("id"));
            } catch (Exception ignored) {
                /* A malformed historical record remains unavailable. */
            }
        }
        jdbc.update(
                "UPDATE assessment_task SET"
                    + " status='FAILED',error_code='TASK_INTERRUPTED',error_message='应用中断，原任务未完成；请检查输入快照后创建新任务',updated_at=CURRENT_TIMESTAMP"
                    + " WHERE status IN ('PENDING','RUNNING')");
    }
}
