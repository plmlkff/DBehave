package io.dbehave.backend.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonbValidationTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonbValidation validation = new JsonbValidation(mapper);

    @Test
    void acceptsOrdinaryNumbersAndUnicodeWithoutChangingTheirTypesOrPrecision() {
        var decimal = new BigDecimal("1.2300");
        var value = Map.of("числа", List.of(42, Long.MAX_VALUE, 0.25, 1.5f, new BigInteger("123456789012345678901234567890"), decimal),
                "text", "Кириллица и emoji \uD83D\uDE00", "nullable", Arrays.asList(true, null));

        assertThatCode(() -> validation.validate(value, "payload")).doesNotThrowAnyException();
        assertThat(decimal.scale()).isEqualTo(4);
        assertThat(value.get("числа")).isEqualTo(List.of(42, Long.MAX_VALUE, 0.25, 1.5f,
                new BigInteger("123456789012345678901234567890"), new BigDecimal("1.2300")));
    }

    @ParameterizedTest
    @MethodSource("invalidText")
    void rejectsUnsupportedUnicodeInBothValuesAndObjectKeys(String text) {
        assertInvalid(Map.of("nested", List.of(text)));
        assertInvalid(Map.of(text, "value"));
        var failure = assertThrows(InvalidExperimentException.class,
                () -> validation.validate(Map.of(text, text), "payload"));
        assertThat(failure.fieldErrors()).extracting(FieldError::field).containsExactly("payload");
    }

    @ParameterizedTest
    @MethodSource("invalidNumbers")
    void rejectsNumbersThatCannotBeStoredAndReadBack(Object value) {
        assertInvalid(Map.of("number", value));
    }

    @Test
    void usesTheActualMapperReadLimitForExpandedScientificNotation() {
        var factory = JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(8).build()).build();
        var constrained = new JsonbValidation(JsonMapper.builder(factory).build());

        assertThatCode(() -> constrained.validate(List.of(new BigDecimal("1e7"), new BigDecimal("-1e-7")), "payload"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> constrained.validate(new BigDecimal("1e8"), "payload"))
                .isInstanceOf(InvalidExperimentException.class);
        assertThatThrownBy(() -> constrained.validate(new BigDecimal("1e-8"), "payload"))
                .isInstanceOf(InvalidExperimentException.class);
    }

    private void assertInvalid(Object value) {
        assertThatThrownBy(() -> validation.validate(value, "payload"))
                .isInstanceOf(InvalidExperimentException.class);
    }

    private static Stream<String> invalidText() {
        return Stream.of("before\0after", String.valueOf((char) 0xD800), String.valueOf((char) 0xDC00),
                "a" + (char) 0xD800 + "b");
    }

    private static Stream<Object> invalidNumbers() {
        return Stream.of(new BigDecimal("1e1000000"), new BigDecimal("1e-1000000"),
                new BigDecimal("1e1000"), new BigDecimal("1e-1000"),
                Double.NaN, Double.POSITIVE_INFINITY);
    }
}
