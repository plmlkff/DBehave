package io.dbehave.backend.validation;

import java.util.List;

public class InvalidExperimentException extends RuntimeException {
    private final List<FieldError> fieldErrors;

    public InvalidExperimentException(List<FieldError> fieldErrors) {
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
