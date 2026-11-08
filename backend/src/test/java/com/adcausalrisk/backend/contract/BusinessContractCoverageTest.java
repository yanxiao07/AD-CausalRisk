package com.adcausalrisk.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.*;
import java.util.*;

@SpringBootTest
@ActiveProfiles("test")
class BusinessContractCoverageTest {
    @Autowired RequestMappingHandlerMapping mappings;

    private String normalized(String value) {
        return value.replaceAll("\\{[^}]+}", "{}");
    }

    @Test
    void everyBusinessOperationHasAnOpenApiEntryAndNoEntryIsImaginary() throws Exception {
        Set<String> actual = new TreeSet<>(), documented = new TreeSet<>();
        for (var mapping : mappings.getHandlerMethods().keySet())
            for (String path : mapping.getPatternValues())
                if (path.startsWith("/api/"))
                    for (var method : mapping.getMethodsCondition().getMethods())
                        actual.add(method.name() + " " + normalized(path));
        JsonNode contract =
                new ObjectMapper()
                        .readTree(
                                Files.readString(
                                        Path.of("../contracts/java-business.target.openapi.json")));
        var paths = contract.path("paths").fields();
        while (paths.hasNext()) {
            var item = paths.next();
            var operations = item.getValue().fieldNames();
            while (operations.hasNext()) {
                String method = operations.next();
                if (Set.of("get", "post", "patch", "delete", "put").contains(method))
                    documented.add(
                            method.toUpperCase(Locale.ROOT) + " " + normalized(item.getKey()));
            }
        }
        assertThat(documented).containsExactlyInAnyOrderElementsOf(actual);
        assertThat(contract.path("x-implementation-state").asText())
                .isEqualTo("IMPLEMENTED_SYNTHETIC_LOCAL_VALIDATION");
    }
}
