package io.dbehave.plugin.postgres;

import java.util.List;

import static io.dbehave.plugin.FieldSpec.enumeration;
import static io.dbehave.plugin.FieldSpec.integer;

final class LongTxLocksScenario implements PostgresScenario {
    private static final Definition DEFINITION = new Definition(
            "long-tx-locks", "Долгая транзакция и блокировки",
            "Один клиент удерживает блокировку строк, остальные пытаются обновить те же данные.",
            "long-tx-locks",
            List.of(
                    PostgresFields.SHARED_BUFFERS,
                    PostgresFields.MAX_CONNECTIONS,
                    new ContainerField(
                            integer("lock_timeout", "lock_timeout", "Предельное ожидание блокировки, 0 отключает таймаут",
                                    0, 0, 600_000, "мс"),
                            "DBEHAVE_LOCK_TIMEOUT")
            ),
            List.of(
                    PostgresFields.clients(16, 2, "Один клиент блокирует строки, остальные обновляют данные"),
                    PostgresFields.DURATION,
                    new ContainerField(
                            integer("holdSec", "Удержание блокировки", "Как долго блокировщик держит транзакцию",
                                    15, 2, 120, "с"),
                            "HOLD_SEC"),
                    new ContainerField(
                            integer("pauseSec", "Пауза между блокировками", "Пауза после освобождения строк", 5, 0, 30, "с"),
                            "PAUSE_SEC"),
                    new ContainerField(
                            integer("hotRows", "Заблокированные строки", "Размер диапазона, который обновляет блокировщик",
                                    100, 1, 1000, "строк"),
                            "HOT_ROWS"),
                    new ContainerField(
                            integer("hotShare", "Доля обращений к диапазону", "Вероятность обновления строки из этого диапазона",
                                    30, 0, 100, "%"),
                            "HOT_SHARE"),
                    new ContainerField(
                            enumeration("blockerMode", "Режим блокировщика",
                                    "Ожидать без запроса или выполнить pg_sleep внутри транзакции", "idle", "idle", "active"),
                            "BLOCKER_MODE")
            )
    );

    @Override
    public Definition definition() {
        return DEFINITION;
    }
}
