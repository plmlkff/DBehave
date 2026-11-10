package io.dbehave.plugin.postgres;

import java.util.List;

import static io.dbehave.plugin.FieldSpec.enumeration;
import static io.dbehave.plugin.FieldSpec.integer;

final class WriteHeavyScenario implements PostgresScenario {
    private static final Definition DEFINITION = new Definition(
            "write-heavy", "Интенсивная запись",
            "Клиенты параллельно вставляют строки в тестовую таблицу и фиксируют каждую пачку отдельной транзакцией.",
            "write-heavy",
            List.of(
                    PostgresFields.SHARED_BUFFERS,
                    PostgresFields.MAX_CONNECTIONS,
                    new ContainerField(
                            enumeration("synchronous_commit", "synchronous_commit",
                                    "Ждать ли записи журнала WAL на диск перед подтверждением транзакции", "on", "on", "off"),
                            "DBEHAVE_SYNCHRONOUS_COMMIT")
            ),
            List.of(
                    PostgresFields.CLIENTS,
                    PostgresFields.DURATION,
                    new ContainerField(
                            integer("batchSize", "Размер пачки", "Количество строк в одной транзакции", 1, 1, 1000, "строк"),
                            "BATCH_SIZE"),
                    new ContainerField(
                            integer("extraIndexes", "Дополнительные индексы", "Количество индексов сверх первичного ключа",
                                    0, 0, 5, null),
                            "EXTRA_INDEXES"),
                    new ContainerField(
                            integer("payloadBytes", "Размер полезной нагрузки", "Размер данных в каждой вставляемой строке",
                                    200, 50, 4000, "Б"),
                            "PAYLOAD_BYTES")
            )
    );

    @Override
    public Definition definition() {
        return DEFINITION;
    }
}
