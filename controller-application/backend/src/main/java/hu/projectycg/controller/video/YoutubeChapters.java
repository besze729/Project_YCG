package hu.projectycg.controller.video;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** Deterministic display/export formatting; raw millisecond evidence is unchanged. */
public final class YoutubeChapters {
    private YoutubeChapters() {}

    public record Chapter(long startSeconds, String title) {}
    public record Result(long durationMs, List<Chapter> chapters, String text, List<String> warnings) {}

    public static Result generate(JsonNode evidence) {
        if (evidence == null || evidence.path("schemaVersion").asInt() != 1
                || !evidence.path("durationMs").isIntegralNumber() || !evidence.path("durationMs").canConvertToLong()
                || evidence.path("durationMs").asLong() <= 0 || !evidence.path("intervals").isArray()) {
            throw new IllegalArgumentException("Invalid or unsupported boss interval artifact");
        }
        long duration = evidence.path("durationMs").asLong();
        var transitions = new TreeMap<Long, String>();
        transitions.put(0L, "Exploration");
        long previousEnd = 0;
        boolean fractional = false;
        for (var interval : evidence.path("intervals")) {
            var startNode = interval.path("startMs");
            var endNode = interval.path("endMs");
            if (!startNode.isIntegralNumber() || !endNode.isIntegralNumber()
                    || !startNode.canConvertToLong() || !endNode.canConvertToLong()) {
                throw new IllegalArgumentException("Boss interval timestamps must be integer milliseconds");
            }
            long start = startNode.asLong(), end = endNode.asLong();
            if (start < previousEnd || end <= start || end > duration) {
                throw new IllegalArgumentException("Boss intervals must be ordered, nonoverlapping and within the video");
            }
            // YouTube timestamps have whole-second precision. At a collision the
            // latest transition wins, so zero-length chapters are never emitted.
            transitions.put(start / 1000, "BOSS_FIGHT");
            if (end < duration) transitions.put(end / 1000, "Exploration");
            fractional |= start % 1000 != 0 || (end < duration && end % 1000 != 0);
            previousEnd = end;
        }
        var chapters = new ArrayList<Chapter>();
        String previousTitle = null;
        for (var entry : transitions.entrySet()) {
            if (!entry.getValue().equals(previousTitle)) {
                chapters.add(new Chapter(entry.getKey(), entry.getValue()));
                previousTitle = entry.getValue();
            }
        }
        var warnings = new ArrayList<String>();
        if (chapters.size() < 3) warnings.add("YouTube requires at least 3 chapters to display video chapters. The detected timeline is preserved.");
        for (int index = 0; index < chapters.size(); index++) {
            long end = index + 1 < chapters.size() ? chapters.get(index + 1).startSeconds() * 1000 : duration;
            if (end - chapters.get(index).startSeconds() * 1000 < 10_000) {
                warnings.add("Some chapters are shorter than YouTube's 10-second minimum. The detected boundaries have not been extended or merged.");
                break;
            }
        }
        if (fractional) warnings.add("YouTube timestamps use whole seconds: fractional boundaries are rounded down and transitions in the same second are combined. Original millisecond intervals are preserved.");
        var text = new StringBuilder();
        for (var chapter : chapters) text.append(timestamp(chapter.startSeconds())).append(' ').append(chapter.title()).append('\n');
        return new Result(duration, List.copyOf(chapters), text.toString(), List.copyOf(warnings));
    }

    private static String timestamp(long seconds) {
        return seconds < 3600
                ? String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
