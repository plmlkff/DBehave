package io.dbehave.backend.validation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks values that PostgreSQL JSONB stores and the application can read back. */
@Component
public final class JsonbValidation {
    private static final int MAX_INTEGER_DIGITS = 131_072;
    private static final int MAX_FRACTION_DIGITS = 16_383;

    private final JsonMapper json;
    private final int maxNumberLength;

    public JsonbValidation(JsonMapper json) {
        this.json = json;
        this.maxNumberLength = json.tokenStreamFactory().streamReadConstraints().getMaxNumberLength();
    }

    public void validate(Object value, String field) {
        var errors = new ArrayList<FieldError>();
        try {
            inspect(json.valueToTree(value), field, errors);
        } catch (JacksonException exception) {
            errors.add(new FieldError(field, "Значение невозможно преобразовать в JSON"));
        }
        InputValidation.rejectErrors(errors);
    }

    private void inspect(JsonNode node, String field, List<FieldError> errors) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                if (!validText(entry.getKey())) {
                    errors.add(new FieldError(field, "Ключ JSON содержит недопустимый символ"));
                    continue;
                }
                inspect(entry.getValue(), field + "." + entry.getKey(), errors);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (var element : node) {
                inspect(element, field + "[" + index + "]", errors);
                index++;
            }
        } else if (node.isString() && !validText(node.stringValue())) {
            errors.add(new FieldError(field, "Строка содержит недопустимый символ Unicode"));
        } else if (node.isNumber() && !validNumber(node)) {
            errors.add(new FieldError(field, "Число выходит за допустимый диапазон или точность JSONB"));
        }
    }

    private boolean validNumber(JsonNode node) {
        BigDecimal number;
        try {
            number = node.decimalValue();
        } catch (NumberFormatException exception) {
            return false;
        }
        long integerDigits = number.signum() == 0 ? 0 : (long) number.precision() - number.scale();
        if (integerDigits > MAX_INTEGER_DIGITS || number.scale() > MAX_FRACTION_DIGITS) {
            return false;
        }
        // PostgreSQL expands exponents. Jackson limits the digits of that expanded token.
        String plain = number.toPlainString();
        int digits = plain.length() - (number.signum() < 0 ? 1 : 0) - (number.scale() > 0 ? 1 : 0);
        return digits <= maxNumberLength;
    }

    private static boolean validText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\0') {
                return false;
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }
}
