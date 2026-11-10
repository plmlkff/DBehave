package io.dbehave.plugin.postgres.load.scenario;

import io.dbehave.plugin.postgres.load.ScenarioContext;

import java.sql.SQLException;

/** Таблица items для сценариев чтения и смешанной нагрузки. */
final class ItemsTable {
    private ItemsTable() {
    }

    static void create(ScenarioContext ctx, long rows, int fillfactor, boolean categoryIndex) throws SQLException {
        ctx.db().exec("drop table if exists items", """
                create table items (
                    id bigint primary key,
                    category int not null,
                    value numeric(12, 2) not null,
                    payload text not null,
                    updated_at timestamptz not null default now()
                ) with (fillfactor = %d)""".formatted(fillfactor));
        ctx.db().fill("""
                insert into items (id, category, value, payload)
                select g, (g % 1000)::int, round((random() * 1000)::numeric, 2), md5(g::text) || md5((g + 1)::text)
                from generate_series(?::bigint, ?::bigint) g""", rows, ctx.state());
        if (categoryIndex) {
            ctx.db().exec("create index items_category_idx on items (category)");
        }
        ctx.db().exec("vacuum analyze items");
    }
}
