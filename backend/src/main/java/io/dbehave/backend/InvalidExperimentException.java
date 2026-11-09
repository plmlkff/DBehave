package io.dbehave.backend;

import java.util.List;

class InvalidExperimentException extends RuntimeException {
    private final List<FieldError> fieldErrors;

    InvalidExperimentException(List<FieldError> fieldErrors) {
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
