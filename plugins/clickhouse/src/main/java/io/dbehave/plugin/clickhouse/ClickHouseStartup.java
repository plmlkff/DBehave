package io.dbehave.plugin.clickhouse;

import java.util.List;

/** Starts the official image with its configuration in writable tmpfs and bounded background thread pools. */
final class ClickHouseStartup {
    private ClickHouseStartup() {
    }

    static List<String> command() {
        return List.of("bash", "-ec", """
                cp -a /etc/clickhouse-server /tmp/clickhouse-config
                cat > /tmp/clickhouse-config/users.d/dbehave.xml <<'XML'
                <clickhouse>
                  <users>
                    <default remove="remove"/>
                    <dbehave>
                      <password from_env="CLICKHOUSE_PASSWORD"/>
                      <networks><ip>::/0</ip></networks>
                      <profile>default</profile>
                      <quota>default</quota>
                      <access_management>1</access_management>
                    </dbehave>
                  </users>
                </clickhouse>
                XML
                cat > /tmp/clickhouse-config/config.d/dbehave.xml <<'XML'
                <clickhouse>
                  <logger><level>warning</level><console>1</console></logger>
                  <max_thread_pool_size>128</max_thread_pool_size>
                  <background_pool_size>4</background_pool_size>
                  <background_schedule_pool_size>4</background_schedule_pool_size>
                  <background_buffer_flush_schedule_pool_size>1</background_buffer_flush_schedule_pool_size>
                  <background_message_broker_schedule_pool_size>1</background_message_broker_schedule_pool_size>
                  <background_distributed_schedule_pool_size>1</background_distributed_schedule_pool_size>
                  <merge_tree>
                    <number_of_free_entries_in_pool_to_execute_mutation>2</number_of_free_entries_in_pool_to_execute_mutation>
                    <number_of_free_entries_in_pool_to_execute_optimize_entire_partition>2</number_of_free_entries_in_pool_to_execute_optimize_entire_partition>
                    <number_of_free_entries_in_pool_to_lower_max_size_of_merge>2</number_of_free_entries_in_pool_to_lower_max_size_of_merge>
                  </merge_tree>
                </clickhouse>
                XML
                chown -R clickhouse:clickhouse /tmp/clickhouse-config /var/lib/clickhouse /var/log/clickhouse-server
                exec clickhouse su "$(id -u clickhouse):$(id -g clickhouse)" clickhouse-server --config-file=/tmp/clickhouse-config/config.xml
                """);
    }
}
