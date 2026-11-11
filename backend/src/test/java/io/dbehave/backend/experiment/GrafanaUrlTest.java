package io.dbehave.backend.experiment;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrafanaUrlTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void repeatsGrafanaVariableForEachRunInInputOrder() {
        String actual = GrafanaUrl.fromTemplate(
                "http://localhost:3001/d/dbehave-postgres?var-run_id={runId}", List.of(FIRST, SECOND));

        assertThat(actual).isEqualTo("http://localhost:3001/d/dbehave-postgres?var-run_id=" + FIRST + "&var-run_id=" + SECOND + "&kiosk=1");
    }

    @Test
    void preservesEncodedParametersRepeatedOtherVariablesTimeRangeAndFragment() {
        String template = "https://grafana.example/d/test?from=1700000000000&to=now&var-source=a%2Fb%20c"
                + "&var-source=x+y&filter=key%3Dvalue%26other%3D%2520&var-run_id={runId}#panel%2Fone";

        String actual = GrafanaUrl.fromTemplate(template, List.of(FIRST, SECOND));

        assertThat(actual).isEqualTo("https://grafana.example/d/test?from=1700000000000&to=now&var-source=a%2Fb%20c"
                + "&var-source=x+y&filter=key%3Dvalue%26other%3D%2520&var-run_id=" + FIRST
                + "&var-run_id=" + SECOND + "&kiosk=1#panel%2Fone");
    }

    @Test
    void supportsAlternativeVariableNamesAndOneRun() {
        assertThat(GrafanaUrl.fromTemplate(
                "https://grafana.example/d/test?var-experiment={runId}&kiosk", List.of(FIRST)))
                .isEqualTo("https://grafana.example/d/test?var-experiment=" + FIRST + "&kiosk=1");
    }

    @Test
    void supportsEncodedPlaceholderAndPreservesEncodedVariableName() {
        assertThat(GrafanaUrl.fromTemplate(
                "https://grafana.example/d/test?var-run%5Fid=%7BrunId%7D", List.of(FIRST, SECOND)))
                .isEqualTo("https://grafana.example/d/test?var-run%5Fid=" + FIRST + "&var-run%5Fid=" + SECOND + "&kiosk=1");
    }

    @Test
    void expandsEachSupportedPlaceholderWhenSeveralVariablesUseTheRunId() {
        assertThat(GrafanaUrl.fromTemplate(
                "https://grafana.example/d/test?var-first={runId}&x=1&var-second={runId}", List.of(FIRST, SECOND)))
                .isEqualTo("https://grafana.example/d/test?var-first=" + FIRST + "&var-first=" + SECOND
                        + "&x=1&var-second=" + FIRST + "&var-second=" + SECOND + "&kiosk=1");
    }

    @Test
    void preservesEmptyQueryComponentsAndEmptyFragment() {
        assertThat(GrafanaUrl.fromTemplate(
                "https://grafana.example/d/test?&var-run_id={runId}&&x=&flag#", List.of(FIRST)))
                .isEqualTo("https://grafana.example/d/test?&var-run_id=" + FIRST + "&&x=&flag&kiosk=1#");
    }

    @Test
    void comparisonNormalizesExistingKioskValues() {
        assertThat(GrafanaUrl.fromTemplate(
                "https://grafana.example/d/test?kiosk=tv&var-run_id={runId}&kiosk=other&filter=a%2Bb",
                List.of(FIRST, SECOND)))
                .isEqualTo("https://grafana.example/d/test?var-run_id=" + FIRST + "&var-run_id=" + SECOND
                        + "&filter=a%2Bb&kiosk=1");
    }

    @Test
    void leavesNullLegacyLinkAbsent() {
        assertThat(GrafanaUrl.withKiosk(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://grafana.example/d/test", "https://grafana.example/d/test?"})
    void addsKioskToLinksWithoutQueryParameters(String url) {
        assertThat(GrafanaUrl.withKiosk(url)).isEqualTo("https://grafana.example/d/test?kiosk=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kiosk", "kiosk=", "kiosk=1", "kiosk=tv", "kiosk=other", "kiosk=tv&kiosk=other",
            "kiosk&kiosk=&kiosk=tv", "%6Biosk=tv"
    })
    void normalizesAnyKioskValueAndDuplicates(String query) {
        assertThat(GrafanaUrl.withKiosk("https://grafana.example/d/test?" + query + "#panel%2Fone"))
                .isEqualTo("https://grafana.example/d/test?kiosk=1#panel%2Fone");
    }

    @Test
    void preservesRawQueryEncodingOtherParametersAndFragmentInOrdinaryLinks() {
        String original = "https://grafana.example/d/test?from=now-1h&kiosk=tv&var-source=a%2Fb%20c"
                + "&var-source=x+y&filter=key%3Dvalue%26other%3D%2520&kiosk=other&to=now#panel%2Fone";
        String expected = "https://grafana.example/d/test?from=now-1h&var-source=a%2Fb%20c"
                + "&var-source=x+y&filter=key%3Dvalue%26other%3D%2520&to=now&kiosk=1#panel%2Fone";

        assertThat(GrafanaUrl.withKiosk(original)).isEqualTo(expected);
        assertThat(GrafanaUrl.withKiosk(expected)).isEqualTo(expected);
    }

    @Test
    void insertsKioskBeforeFragmentEvenWhenFragmentContainsQuestionMark() {
        assertThat(GrafanaUrl.withKiosk("https://grafana.example/d/test#panel?kiosk=tv"))
                .isEqualTo("https://grafana.example/d/test?kiosk=1#panel?kiosk=tv");
        assertThat(GrafanaUrl.withKiosk("https://grafana.example/d/test?#"))
                .isEqualTo("https://grafana.example/d/test?kiosk=1#");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://grafana.example/d/test",
            "https://grafana.example/d/test?",
            "https://grafana.example/d/test?var-run_id=literal",
            "https://grafana.example/d/{runId}?var-run_id=literal",
            "https://{runId}.example/d/test?var-run_id={runId}",
            "https://grafana.example/d/{runId}?var-run_id={runId}",
            "https://grafana.example/d/test?run_id={runId}",
            "https://grafana.example/d/test?var-={runId}",
            "https://grafana.example/d/test?var-run_id=prefix-{runId}",
            "https://grafana.example/d/test?var-run_id={runId}{runId}",
            "https://grafana.example/d/test?var-{runId}=value",
            "https://grafana.example/d/test?var-run_id={runId}#run-{runId}",
            "https://grafana.example/d/test?var-run_id={runId}#%7BrunId%7D",
            "https://grafana.example/d/test#?var-run_id={runId}",
            "https://grafana.example/d/test?var-run_id={runId}&other=%7BrunId%7D",
            "javascript:alert(1)?var-run_id={runId}",
            "/d/test?var-run_id={runId}"
    })
    void rejectsUnsupportedTemplateLocationsOrMissingVariable(String template) {
        assertThatThrownBy(() -> GrafanaUrl.fromTemplate(template, List.of(FIRST, SECOND)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyRunSelection() {
        assertThatThrownBy(() -> GrafanaUrl.fromTemplate(
                "https://grafana.example/d/test?var-run_id={runId}", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
