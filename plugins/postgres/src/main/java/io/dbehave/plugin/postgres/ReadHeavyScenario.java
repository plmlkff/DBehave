package io.dbehave.plugin.postgres;

import java.util.List;

import static io.dbehave.plugin.FieldSpec.bool;
import static io.dbehave.plugin.FieldSpec.enumeration;
import static io.dbehave.plugin.FieldSpec.integer;
import static io.dbehave.plugin.FieldSpec.number;

final class ReadHeavyScenario implements PostgresScenario {
    private static final Definition DEFINITION = new Definition(
            "read-heavy", "Интенсивное чтение",
            "Сценарий читает тестовую таблицу и позволяет сравнить влияние индекса и распределения обращений.",
            "read-heavy",
            List.of(
                    PostgresFields.SHARED_BUFFERS,
                    PostgresFields.MAX_CONNECTIONS,
                    new ContainerField(
                            number("random_page_cost", "random_page_cost", "Оценка стоимости случайного чтения страницы",
                                    4.0, 0.1, 100.0, null),
                            "DBEHAVE_RANDOM_PAGE_COST")
            ),
            List.of(
                    PostgresFields.CLIENTS,
                    PostgresFields.DURATION,
                    new ContainerField(
                            integer("datasetRows", "Количество строк", "Начальный объём тестовой таблицы",
                                    1_000_000, 100_000, 10_000_000, "строк"),
                            "ROWS"),
                    new ContainerField(
                            bool("categoryIndex", "Индекс по категории", "Добавить индекс для выборок по категории", false),
                            "CATEGORY_INDEX"),
                    new ContainerField(
                            enumeration("distribution", "Распределение чтений",
                                    "Равномерное или с повышенной частотой обращений к части строк",
                                    "uniform", "uniform", "hotspot"),
                            "DISTRIBUTION")
            )
    );

    @Override
    public Definition definition() {
        return DEFINITION;
    }
}
