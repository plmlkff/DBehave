package io.dbehave.plugin.postgres.load.scenario;

import java.sql.SQLException;

/**
 * Сценарий нагрузки. Движок вызывает {@link #prepare()}, затем у каждого клиента в цикле
 * {@link ClientSession#step()}, затем {@link #verify()}.
 */
public interface Scenario {
    /** Создать схему и данные. Прогресс сообщается через {@code RunState.progress}. */
    void prepare() throws SQLException;

    /** Открыть сессию клиента: обычно своё соединение и подготовленные запросы. */
    ClientSession openSession(int clientIndex) throws SQLException;

    /** Проверки после нагрузки: результат уходит в метрики {@code dbehave_check_*}. */
    default void verify() throws SQLException {
    }

    interface ClientSession extends AutoCloseable {
        /** Одна итерация клиента. Операции замеряются через {@code Metrics.measure}. */
        void step() throws SQLException;

        @Override
        void close();
    }
}
