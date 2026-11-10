package io.dbehave.plugin.postgres;

import io.dbehave.plugin.FieldSpec;

import java.math.BigDecimal;

/**
 * Настройка PostgreSQL: поле формы и единица измерения, в которой значение передаётся в {@code postgres -c}.
 *
 * @param pgUnit суффикс для PostgreSQL ({@code MB}, {@code kB}, {@code ms}, {@code s}) или {@code null}
 */
record PgSetting(FieldSpec spec, String pgUnit) {
    String key() {
        return spec.key();
    }

    /** Значение в формате PostgreSQL: {@code 128} → {@code 128MB}, {@code true} → {@code on}, {@code -1} → {@code -1}. */
    String format(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "on" : "off";
        }
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            boolean auto = decimal.signum() < 0; // -1 у PostgreSQL означает «автоматически», единица к нему не пишется
            return decimal.toPlainString() + (pgUnit == null || auto ? "" : pgUnit);
        }
        return String.valueOf(value);
    }
}
