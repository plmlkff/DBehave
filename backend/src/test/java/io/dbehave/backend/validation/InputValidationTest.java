package io.dbehave.backend.validation;

import io.dbehave.plugin.FieldSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputValidationTest {
    @Test
    void distinguishesMissingDefaultsFromExplicitNullAndRejectsUndeclaredValues() {
        var specs = List.of(FieldSpec.integer("rps", "RPS", "", 100, 1, 1000, null));
        var errors = new ArrayList<FieldError>();
        assertThat(InputValidation.normalize("params", specs, null, errors)).containsEntry("rps", 100);
        assertThat(errors).isEmpty();

        var input = new HashMap<String, Object>();
        input.put("rps", null);
        input.put("typo", 5);
        assertThat(InputValidation.normalize("params", specs, input, errors)).isEmpty();
        assertThat(errors).extracting(FieldError::field).containsExactlyInAnyOrder("params.rps", "params.typo");
    }

    @Test
    void rejectsNonfiniteCpuAndMemoryOutsideTheSupportedIntegerRange() {
        for (Object invalid : List.of(0, -0.5, Double.NaN, Double.POSITIVE_INFINITY, "2")) {
            var errors = new ArrayList<FieldError>();
            InputValidation.resources("loader", Map.of(), Map.of("core_max", invalid), errors);
            assertThat(errors).extracting(FieldError::field).containsExactly("loader.core_max");
        }
        for (Object invalid : List.of(0, -1, 1.5, new BigInteger("9223372036854775808"), "1024")) {
            var errors = new ArrayList<FieldError>();
            InputValidation.resources("database", Map.of(), Map.of("mem_max", invalid), errors);
            assertThat(errors).extracting(FieldError::field).containsExactly("database.mem_max");
        }
    }

    @Test
    void mergesOnlyResourceDefaultsAndAllowsLongMemoryLimits() {
        var errors = new ArrayList<FieldError>();
        var result = InputValidation.resources("loader",
                Map.of("core_max", 0.25, "mem_max", 1024, "environment", Map.of("X", "Y")),
                Map.of("mem_max", Long.MAX_VALUE), errors);
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of("core_max", 0.25, "mem_max", Long.MAX_VALUE));
        assertThat(errors).isEmpty();

        var overrides = new HashMap<String, Object>();
        overrides.put("core_max", null);
        overrides.put("environment", Map.of("X", "changed"));
        InputValidation.resources("loader", Map.of("core_max", 1), overrides, errors);
        assertThat(errors).extracting(FieldError::field)
                .containsExactlyInAnyOrder("loader.core_max", "loader.environment");
    }
}
