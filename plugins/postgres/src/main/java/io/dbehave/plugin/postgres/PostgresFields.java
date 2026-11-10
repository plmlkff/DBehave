package io.dbehave.plugin.postgres;

import io.dbehave.plugin.postgres.PostgresScenario.ContainerField;

import static io.dbehave.plugin.FieldSpec.integer;

final class PostgresFields {
    static final ContainerField SHARED_BUFFERS = new ContainerField(
            integer("shared_buffers", "shared_buffers", "Память для общего кеша страниц", 256, 16, 1024, "МБ"),
            "DBEHAVE_SHARED_BUFFERS", "MB");
    static final ContainerField MAX_CONNECTIONS = new ContainerField(
            integer("max_connections", "max_connections", "Максимум подключений к тестовой БД", 100, 10, 500, null),
            "DBEHAVE_MAX_CONNECTIONS");
    static final ContainerField CLIENTS = clients(32, 1, "Число исполнителей нагрузки");
    static final ContainerField DURATION = duration(60);

    private PostgresFields() {
    }

    static ContainerField clients(int defaultValue, int min, String description) {
        return new ContainerField(integer("clients", "Параллельные клиенты", description, defaultValue, min, 128, null),
                "CLIENTS");
    }

    static ContainerField duration(int defaultValue) {
        return new ContainerField(
                integer("durationSec", "Длительность", "Продолжительность измерения", defaultValue, 1, 600, "с"),
                "DURATION_SEC");
    }
}
