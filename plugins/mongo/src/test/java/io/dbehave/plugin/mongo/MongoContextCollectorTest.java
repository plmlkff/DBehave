package io.dbehave.plugin.mongo;

import io.dbehave.plugin.FieldSpec;
import io.dbehave.plugin.PluginDefinition;
import io.dbehave.plugin.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

class MongoContextCollectorTest {
    private static final MongoContextCollector COLLECTOR = new MongoContextCollector();
    private static final PluginDefinition DEFINITION = MongoContextCollector.defaultDefinition();

    static List<Scenario> scenarios() {
        return DEFINITION.scenarios();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void defaultsProduceContextsForEveryScenario(Scenario scenario) {
        var contexts = COLLECTOR.collect(DEFINITION, scenario, defaults(scenario.settings()), defaults(scenario.params()));

        assertThat(contexts.databaseImageContext().get("command"))
                .asList().contains("mongod", "--bind_ip_all", "--wiredTigerCacheSizeGB=0.25");
        assertThat(contexts.loaderImageContext().get("environment"))
                .asInstanceOf(MAP)
                .containsEntry("SCENARIO", scenario.id())
                .containsEntry("DB_HOST", "database")
                .containsEntry("DB_PORT", "27017")
                .containsEntry("CLIENTS", "16")
                .containsEntry("MONGO_CACHE_SIZE_MB", "256");
    }

    @Test
    void sendsStorageSettingsToMongodAndAcknowledgmentToLoader() {
        var scenario = scenarios().getFirst();
        var settings = defaults(scenario.settings());
        settings.putAll(Map.of("blockCompressor", "zstd", "journalAcknowledged", true));
        var contexts = COLLECTOR.collect(DEFINITION, scenario, settings, defaults(scenario.params()));

        assertThat(contexts.databaseImageContext().get("command")).asList()
                .contains("--wiredTigerCollectionBlockCompressor=zstd")
                .noneMatch(argument -> argument.toString().contains("journalAcknowledged"));
        assertThat(contexts.loaderImageContext().get("environment"))
                .asInstanceOf(MAP)
                .containsEntry("MONGO_JOURNAL_ACKNOWLEDGED", "true")
                .containsEntry("MONGO_BLOCK_COMPRESSOR", "zstd")
                .containsEntry("BATCH_SIZE", "1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "snappy", "zstd"})
    void sendsJournalCompressorToMongodAndLoader(String compressor) {
        var scenario = scenarios().getFirst();
        var settings = defaults(scenario.settings());
        settings.put("journalCompressor", compressor);
        var contexts = COLLECTOR.collect(DEFINITION, scenario, settings, defaults(scenario.params()));

        assertThat(contexts.databaseImageContext().get("command")).asList()
                .contains("--wiredTigerJournalCompressor=" + compressor, "--wiredTigerCollectionBlockCompressor=snappy");
        assertThat(contexts.loaderImageContext().get("environment")).asInstanceOf(MAP)
                .containsEntry("MONGO_JOURNAL_COMPRESSOR", compressor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sendsPrefixCompressionToMongodAndLoader(boolean enabled) {
        var scenario = scenarios().getLast();
        var contexts = COLLECTOR.collect(DEFINITION, scenario, Map.of("prefixCompression", enabled),
                defaults(scenario.params()));

        assertThat(contexts.databaseImageContext().get("command")).asList()
                .contains("--wiredTigerIndexPrefixCompression=" + enabled);
        assertThat(contexts.loaderImageContext().get("environment")).asInstanceOf(MAP)
                .containsEntry("MONGO_PREFIX_COMPRESSION", Boolean.toString(enabled));
    }

    @Test
    void sendsReportSettingsAndRejectsZeroOperationWeights() {
        var scenario = scenarios().get(1);
        var params = defaults(scenario.params());
        var settings = Map.<String, Object>of("cacheSizeMB", 333, "allowDiskUse", false);
        var contexts = COLLECTOR.collect(DEFINITION, scenario, settings, params);
        assertThat(contexts.databaseImageContext().get("command")).asList()
                .contains("--wiredTigerCacheSizeGB=0.3251953125");
        assertThat(contexts.loaderImageContext().get("environment"))
                .asInstanceOf(MAP)
                .containsEntry("MONGO_CACHE_SIZE_MB", "333")
                .containsEntry("MONGO_ALLOW_DISK_USE", "false")
                .containsEntry("REPORT_ROWS", "100000");

        params.putAll(Map.of("pointWeight", 0, "rangeWeight", 0, "reportWeight", 0));
        assertThatThrownBy(() -> COLLECTOR.collect(DEFINITION, scenario, settings, params))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Сумма весов");
    }

    private static Map<String, Object> defaults(List<FieldSpec> fields) {
        var values = new HashMap<String, Object>();
        fields.forEach(field -> values.put(field.key(), field.defaultValue()));
        return values;
    }
}
