package io.dbehave.plugin.postgres;

import java.util.List;

import static io.dbehave.plugin.FieldSpec.bool;
import static io.dbehave.plugin.FieldSpec.enumeration;
import static io.dbehave.plugin.FieldSpec.integer;

final class IsolationScenario implements PostgresScenario {
    private static final Definition DEFINITION = new Definition(
            "isolation", "Проверка уровней изоляции",
            "Транзакции параллельно обновляют счётчики. Проверка сравнивает итоговую сумму с числом успешных обновлений.",
            "isolation",
            List.of(PostgresFields.SHARED_BUFFERS, PostgresFields.MAX_CONNECTIONS),
            List.of(
                    PostgresFields.clients(16, 2, "Количество клиентов, конкурирующих за счётчики"),
                    PostgresFields.duration(20),
                    new ContainerField(
                            enumeration("isolationLevel", "Уровень изоляции", "Уровень для каждой транзакции сценария",
                                    "READ COMMITTED", "READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"),
                            "ISOLATION_LEVEL"),
                    new ContainerField(
                            integer("counterRows", "Количество счётчиков", "Меньшее число строк увеличивает конкуренцию",
                                    10, 1, 1000, "строк"),
                            "COUNTER_ROWS"),
                    new ContainerField(
                            integer("thinkMs", "Пауза после чтения", "Расширяет окно для конкурентного обновления",
                                    5, 0, 100, "мс"),
                            "THINK_MS"),
                    new ContainerField(
                            enumeration("writeMode", "Способ обновления", "Чтение и запись, блокировка строки или атомарное обновление",
                                    "read-modify-write", "read-modify-write", "select-for-update", "atomic-update"),
                            "WRITE_MODE"),
                    new ContainerField(
                            bool("retryOnSerializationFailure", "Повтор при ошибке сериализации",
                                    "Повторять транзакцию после ошибки сериализации", false),
                            "RETRY_ON_SERIALIZATION_FAILURE")
            )
    );

    @Override
    public Definition definition() {
        return DEFINITION;
    }
}
