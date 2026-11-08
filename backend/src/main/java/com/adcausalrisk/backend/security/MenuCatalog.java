package com.adcausalrisk.backend.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The menu vocabulary returned in {@code CurrentUser.menus}.
 *
 * <p>The frozen contract declares {@code menus} as {@code array<string>} with no enum, so these
 * codes are a team convention rather than a contract guarantee: the backend decides which codes a
 * role receives, and the frontend maps codes to routes and labels. The list is recorded here, in
 * one place, exactly as it was agreed for this PR.
 *
 * <table>
 *   <caption>Codes by role, from docs/Design_Guide.md section 1</caption>
 *   <tr><th>Code</th><th>Area</th><th>Roles</th></tr>
 *   <tr><td>users</td><td>user and role administration</td><td>ADMIN</td></tr>
 *   <tr><td>system</td><td>model configuration and service status</td><td>ADMIN</td></tr>
 *   <tr><td>audit</td><td>audit log</td><td>ADMIN</td></tr>
 *   <tr><td>subjects</td><td>subject list and detail</td><td>RESEARCHER, DOCTOR</td></tr>
 *   <tr><td>imports</td><td>CSV preview and commit</td><td>RESEARCHER</td></tr>
 *   <tr><td>quality</td><td>quality checks</td><td>RESEARCHER</td></tr>
 *   <tr><td>tasks</td><td>assessment tasks</td><td>RESEARCHER</td></tr>
 *   <tr><td>graph</td><td>causal graph and explanations</td><td>RESEARCHER, DOCTOR</td></tr>
 *   <tr><td>reviews</td><td>human review of results</td><td>DOCTOR</td></tr>
 *   <tr><td>followups</td><td>follow-up plans</td><td>DOCTOR, PATIENT</td></tr>
 *   <tr><td>reports</td><td>report export</td><td>RESEARCHER, DOCTOR</td></tr>
 *   <tr><td>research</td><td>read-only research evidence</td><td>RESEARCHER</td></tr>
 *   <tr><td>summary</td><td>patient-facing summary</td><td>PATIENT</td></tr>
 * </table>
 *
 * <p>Menus are visual affordances only. Every operation is authorised again on the server
 * (AGENTS.md: hiding a UI element is not access control).
 */
public final class MenuCatalog {

    private static final Map<String, List<String>> MENUS_BY_ROLE =
            Map.of(
                    "ADMIN", List.of("audit", "system", "users"),
                    "RESEARCHER",
                            List.of(
                                    "graph",
                                    "imports",
                                    "quality",
                                    "reports",
                                    "research",
                                    "subjects",
                                    "tasks"),
                    "DOCTOR", List.of("followups", "graph", "reports", "reviews", "subjects"),
                    "PATIENT", List.of("followups", "summary"));

    private MenuCatalog() {}

    /**
     * The union of the menus of every role the account holds, deduplicated and sorted so the
     * response is stable. An unknown role contributes nothing rather than failing the login.
     */
    public static List<String> menusFor(Collection<String> roleCodes) {
        if (roleCodes.contains("PATIENT")) return MENUS_BY_ROLE.get("PATIENT");
        return roleCodes.stream()
                .flatMap(roleCode -> MENUS_BY_ROLE.getOrDefault(roleCode, List.of()).stream())
                .distinct()
                .sorted()
                .toList();
    }
}
