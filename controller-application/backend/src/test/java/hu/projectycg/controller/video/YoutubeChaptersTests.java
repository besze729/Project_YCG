package hu.projectycg.controller.video;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class YoutubeChaptersTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    private YoutubeChapters.Result generate(long duration, long... bounds) {
        var evidence = mapper.createObjectNode().put("schemaVersion", 1).put("durationMs", duration);
        var intervals = evidence.putArray("intervals");
        for (int i = 0; i < bounds.length; i += 2) intervals.addObject().put("startMs", bounds[i]).put("endMs", bounds[i + 1]);
        return YoutubeChapters.generate(evidence);
    }

    @Test void fillsTheEntireTimelineWithBossAndExplorationChapters() {
        var result = generate(900000, 21000, 110000, 360000, 633000);
        assertThat(result.text()).isEqualTo("00:00 Exploration\n00:21 BOSS_FIGHT\n01:50 Exploration\n06:00 BOSS_FIGHT\n10:33 Exploration\n");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.chapters()).hasSize(5);
    }

    @Test void handlesNoFightsAndVideoEdgesWithoutAnEndMarker() {
        assertThat(generate(60000).text()).isEqualTo("00:00 Exploration\n");
        assertThat(generate(60000, 0, 60000).text()).isEqualTo("00:00 BOSS_FIGHT\n");
        assertThat(generate(60000, 0, 10000).text()).isEqualTo("00:00 BOSS_FIGHT\n00:10 Exploration\n");
        assertThat(generate(60000, 20000, 60000).text()).isEqualTo("00:00 Exploration\n00:20 BOSS_FIGHT\n");
    }

    @Test void doesNotInventExplorationBetweenTouchingFights() {
        assertThat(generate(60000, 10000, 20000, 20000, 30000).text())
                .isEqualTo("00:00 Exploration\n00:10 BOSS_FIGHT\n00:30 Exploration\n");
    }

    @Test void usesHoursForLongRecordings() {
        assertThat(generate(37_000_000, 3_599_000, 36_000_000).text())
                .isEqualTo("00:00 Exploration\n59:59 BOSS_FIGHT\n10:00:00 Exploration\n");
    }

    @Test void preservesShortChaptersAndReportsYoutubeLimitsSeparately() {
        var result = generate(30000, 1000, 2000);
        assertThat(result.text()).isEqualTo("00:00 Exploration\n00:01 BOSS_FIGHT\n00:02 Exploration\n");
        assertThat(result.warnings()).anyMatch(w -> w.contains("10-second"));
        assertThat(generate(1000).warnings()).hasSize(2);
        assertThat(generate(60000).warnings()).hasSize(1);
        assertThat(result.text()).doesNotContain("YouTube", "minimum", "requires");
    }

    @Test void roundsOnlyAtExportAndEliminatesZeroLengthChapters() {
        var result = generate(63616, 21500, 42900);
        assertThat(result.text()).isEqualTo("00:00 Exploration\n00:21 BOSS_FIGHT\n00:42 Exploration\n");
        assertThat(result.warnings()).anyMatch(w -> w.contains("rounded down"));
        assertThat(generate(60000, 21100, 21900).text()).isEqualTo("00:00 Exploration\n");
        assertThat(generate(63616, 21000, 63616).text()).isEqualTo("00:00 Exploration\n00:21 BOSS_FIGHT\n");
    }

    @Test void rejectsInvalidEvidenceInsteadOfShowingPlausibleOutput() {
        for (long[] bounds : new long[][]{{-1, 1000}, {1000, 1000}, {2000, 1000}, {1000, 60001},
                {20000, 30000, 10000, 15000}, {1000, 3000, 2000, 4000}}) {
            assertThatThrownBy(() -> generate(60000, bounds)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> generate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YoutubeChapters.generate(mapper.readTree("{\"schemaVersion\":2,\"durationMs\":1000,\"intervals\":[]}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YoutubeChapters.generate(mapper.readTree("{\"schemaVersion\":1,\"durationMs\":1000,\"intervals\":[{\"startMs\":0.1,\"endMs\":1000}]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
