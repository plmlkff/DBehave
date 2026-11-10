package io.dbehave.plugin.postgres.load;

import java.sql.SQLException;
import java.util.Locale;

/** Вид ошибки PostgreSQL по SQLSTATE. Значение идёт в метку {@code kind} метрики ошибок. */
public enum ErrorKind {
    SERIALIZATION_FAILURE,
    DEADLOCK,
    LOCK_TIMEOUT,
    STATEMENT_TIMEOUT,
    IDLE_SESSION_TERMINATED,
    TOO_MANY_CONNECTIONS,
    CONNECTION_ERROR,
    CONSTRAINT_VIOLATION,
    OTHER;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** После таких ошибок соединение больше не годится, и сессию клиента нужно открыть заново. */
    public boolean breaksConnection() {
        return this == IDLE_SESSION_TERMINATED || this == TOO_MANY_CONNECTIONS || this == CONNECTION_ERROR;
    }

    public static ErrorKind of(SQLException e) {
        String state = e.getSQLState();
        if (state == null) {
            return CONNECTION_ERROR;
        }
        return switch (state) {
            case "40001" -> SERIALIZATION_FAILURE;
            case "40P01" -> DEADLOCK;
            case "55P03" -> LOCK_TIMEOUT;
            case "57014" -> STATEMENT_TIMEOUT;
            case "25P03" -> IDLE_SESSION_TERMINATED;
            case "53300" -> TOO_MANY_CONNECTIONS;
            case "57P01", "57P02", "57P03" -> CONNECTION_ERROR;
            default -> state.startsWith("08") ? CONNECTION_ERROR
                    : state.startsWith("23") ? CONSTRAINT_VIOLATION
                    : OTHER;
        };
    }
}
