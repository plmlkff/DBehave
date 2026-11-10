package io.dbehave.plugin.postgres;

import java.util.List;

import static io.dbehave.plugin.FieldSpec.integer;

final class MixedScenario implements PostgresScenario {
    private static final Definition DEFINITION = new Definition(
            "mixed", "Смешанная нагрузка",
            "Сценарий создаёт тестовую таблицу и выполняет чтения и записи. Доля записей дополняет долю чтений до 100%.",
            "mixed",
            List.of(PostgresFields.SHARED_BUFFERS, PostgresFields.MAX_CONNECTIONS),
            List.of(
                    PostgresFields.CLIENTS,
                    PostgresFields.DURATION,
                    new ContainerField(
                            integer("rows", "Количество строк", "Начальный объём тестовой таблицы", 1_000_000, 1, 10_000_000, "строк"),
                            "ROWS"),
                    new ContainerField(
                            integer("readPercent", "Доля чтений", "Доля записей рассчитывается автоматически", 70, 0, 100, "%"),
                            "READ_PERCENT")
            )
    );

    @Override
    public Definition definition() {
        return DEFINITION;
    }
}
