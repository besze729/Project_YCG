package hu.projectycg.controller.video;

import jakarta.annotation.PreDestroy;
import hu.projectycg.controller.knowledge.KnowledgeBaseLookup;
import hu.projectycg.controller.knowledge.KnowledgeBaseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ProxyJobs {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProxyJobs.class);
    private final VideoProcessingClient client;
    private final KnowledgeBaseLookup knowledgeBase;
    private final Path root;
    private final BigDecimal samplingIntervalSeconds;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ProxyJobs(VideoProcessingClient client, VideoProcessingProperties properties, KnowledgeBaseLookup knowledgeBase) {
        this.client = client;
        this.knowledgeBase = knowledgeBase;
        root = properties.outputRoot().toAbsolutePath().normalize();
        samplingIntervalSeconds = properties.samplingIntervalSeconds();
    }

    public record Video(String name, String inputPath, String outputPath, String framesDirectory,
                        String bossDetectionsPath, String bossRefinementDirectory) {}

    public final class Job {
        final String id;
        final String gameName;
        final List<Video> videos;
        final Path directory;
        final BigDecimal samplingIntervalSeconds;
        volatile String state = "running";
        volatile String message = "Video preprocessing started.";
        long sequence;

        Job(String id, String gameName, List<Video> videos, Path directory, BigDecimal samplingIntervalSeconds) {
            this.id = id; this.gameName = gameName; this.videos = videos; this.directory = directory;
            this.samplingIntervalSeconds = samplingIntervalSeconds;
        }

        public synchronized ObjectNode snapshot() {
            var result = mapper.createObjectNode().put("jobId", id).put("gameName", gameName)
                    .put("state", state).put("message", message);
            result.set("videos", mapper.valueToTree(videos));
            result.put("samplingIntervalSeconds", samplingIntervalSeconds);
            result.put("resultsAvailable", "completed".equals(state) && hasIntervalArtifacts());
            return result;
        }

        private boolean hasIntervalArtifacts() {
            return !videos.isEmpty() && videos.stream().allMatch(video -> video.bossRefinementDirectory() != null
                    && Files.isRegularFile(Path.of(video.bossRefinementDirectory()).resolve("boss-intervals.json")));
        }

        synchronized void save() throws IOException {
            Path temp = directory.resolve("job.json.tmp");
            Files.writeString(temp, mapper.writeValueAsString(snapshot()), StandardCharsets.UTF_8);
            Files.move(temp, directory.resolve("job.json"), StandardCopyOption.REPLACE_EXISTING);
        }

        synchronized void emit(ObjectNode event) {
            event.put("id", ++sequence).put("jobId", id).put("timestamp", Instant.now().toString());
            try {
                Files.writeString(directory.resolve("events.jsonl"), mapper.writeValueAsString(event) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        }

        public void stream(long after, OutputStream output) throws IOException {
            // Read the disk journal incrementally: no growing event list in memory.
            try (BufferedReader reader = Files.newBufferedReader(directory.resolve("events.jsonl"), StandardCharsets.UTF_8)) {
                long heartbeat = System.nanoTime();
                while (!Thread.currentThread().isInterrupted()) {
                    String line;
                    boolean done;
                    synchronized (this) {
                        line = reader.readLine();
                        done = !"running".equals(state);
                    }
                    if (line != null) {
                        long id = mapper.readTree(line).path("id").asLong();
                        if (id > after) {
                            output.write(("id: " + id + "\nevent: processing\ndata: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        }
                    } else if (done) return;
                    else {
                        if (System.nanoTime() - heartbeat > 10_000_000_000L) {
                            output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                            output.flush();
                            heartbeat = System.nanoTime();
                        }
                        try { Thread.sleep(200); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }
        }
    }

    public Job start(JsonNode request) throws IOException {
        if (request == null || !request.path("gameName").isString()
                || request.path("gameName").asString().strip().isEmpty()
                || request.path("gameName").asString().strip().length() > 200
                || !request.path("videos").isArray() || request.path("videos").isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "A nonblank gameName (max 200 characters) and at least one video path are required.");
        }
        String gameName = request.path("gameName").asString().strip();
        if (!knowledgeBase.exists(gameName)) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.MISSING_KNOWLEDGE_BASE,
                    "No knowledge base exists for \"" + gameName + "\". Run Build Knowledge Base successfully before processing videos.");
        }
        var sources = new ArrayList<Path>();
        var seen = new HashSet<Path>();
        for (var item : request.path("videos")) {
            if (!item.isString()) throw error(HttpStatus.BAD_REQUEST, "Every video must be an absolute local file path.");
            Path path;
            try { path = Path.of(item.asString()); }
            catch (RuntimeException ex) { throw error(HttpStatus.BAD_REQUEST, "Invalid video path."); }
            if (!path.isAbsolute()) throw error(HttpStatus.BAD_REQUEST, "Video paths must be absolute; browser fake paths are not supported.");
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw error(HttpStatus.NOT_FOUND, "Video is not a readable local file: " + path);
            }
            path = path.toRealPath();
            if (Files.size(path) == 0) throw error(HttpStatus.BAD_REQUEST, "Video is empty: " + path);
            if (!seen.add(path)) throw error(HttpStatus.BAD_REQUEST, "Video selected more than once: " + path);
            sources.add(path);
        }
        if (!busy.compareAndSet(false, true)) throw error(HttpStatus.CONFLICT, "Another preprocessing batch is running.");
        try {
            String id = UUID.randomUUID().toString();
            Path directory = Files.createDirectories(root.resolve(id));
            var videos = new ArrayList<Video>();
            for (int i = 0; i < sources.size(); i++) {
                Path source = sources.get(i);
                videos.add(new Video(source.getFileName().toString(), source.toString(),
                        directory.resolve(String.format("%04d-proxy.mp4", i + 1)).toString(),
                        directory.resolve(String.format("%04d-frames", i + 1)).toString(),
                        directory.resolve(String.format("%04d-boss-detections.jsonl", i + 1)).toString(),
                        directory.resolve(String.format("%04d-boss-refinement", i + 1)).toString()));
            }
            var job = new Job(id, request.path("gameName").asString().strip(), List.copyOf(videos), directory,
                    samplingIntervalSeconds);
            job.save();
            job.emit(mapper.createObjectNode().put("state", "job_started").put("message", job.message));
            jobs.put(id, job);
            workers.submit(() -> process(job));
            return job;
        } catch (Exception ex) { busy.set(false); throw ex; }
    }

    private void process(Job job) {
        String stage = "Video preprocessing";
        try {
            for (int index = 0; index < job.videos.size(); index++) {
                int videoIndex = index;
                var video = job.videos.get(index);
                stage = video.name() + ": proxy creation";
                client.createProxy(job.gameName, Path.of(video.inputPath()), Path.of(video.outputPath()),
                        event -> relay(job, videoIndex, video, "proxy", event));
                stage = video.name() + ": frame sampling";
                client.sampleFrames(job.gameName, Path.of(video.outputPath()), Path.of(video.framesDirectory()),
                        job.samplingIntervalSeconds, event -> relay(job, videoIndex, video, "sample", event));
                stage = video.name() + ": boss fight detection";
                client.detectBossFights(job.gameName, Path.of(video.framesDirectory()), Path.of(video.bossDetectionsPath()),
                        event -> relay(job, videoIndex, video, "boss-detection", event));
                stage = video.name() + ": boss fight boundary refinement";
                client.refineBossFights(job.gameName, Path.of(video.outputPath()), Path.of(video.bossDetectionsPath()),
                        Path.of(video.bossRefinementDirectory()),
                        event -> relay(job, videoIndex, video, "boss-refinement", event));
                stage = video.name() + ": chapter formatting";
                var result = chapters(video);
                Path export = Path.of(video.bossRefinementDirectory()).resolve("youtube-chapters.txt");
                Path temporary = export.resolveSibling("youtube-chapters.txt.tmp");
                Files.writeString(temporary, result.text(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                Files.move(temporary, export, StandardCopyOption.ATOMIC_MOVE);
                job.emit(mapper.createObjectNode().put("state", "chapters_ready").put("videoIndex", index)
                        .put("videoName", video.name()).put("message", video.name() + ": YouTube chapters ready."));
            }
            finish(job, "completed", "YouTube chapters are ready for all videos.");
        } catch (Exception ex) {
            finish(job, "failed", stage + " failed: " + ex.getMessage());
        } finally { busy.set(false); }
    }

    private void relay(Job job, int index, Video video, String operation, JsonNode event) {
        boolean refinementCompleted = operation.equals("boss-refinement") && event.path("state").asString().equals("completed");
        if (refinementCompleted) {
            var intervals = event.path("intervals");
            if (!intervals.isArray()) throw new IllegalArgumentException("Refinement completion is missing intervals");
            long previousEnd = 0;
            for (var interval : intervals) {
                if (!interval.path("startMs").isIntegralNumber() || !interval.path("endMs").isIntegralNumber()
                        || interval.path("startMs").asLong() < previousEnd
                        || interval.path("endMs").asLong() <= interval.path("startMs").asLong()
                        || !interval.path("startClipped").isBoolean() || !interval.path("endClipped").isBoolean()) {
                    throw new IllegalArgumentException("Refinement returned invalid or unordered intervals");
                }
                previousEnd = interval.path("endMs").asLong();
            }
        }
        var enriched = ((ObjectNode) event).deepCopy();
        enriched.put("videoIndex", index).put("videoName", video.name()).put("operation", operation);
        boolean warning = event.path("state").asString().equals("warning");
        String relayMessage = video.name() + ": " + (warning ? event.path("message").asString()
                : operation + " " + event.path("state").asString());
        enriched.put("message", relayMessage);
        if (warning) LOG.warn("{}", relayMessage);
        job.emit(enriched);
        if (refinementCompleted) {
            var intervals = event.path("intervals");
            int number = 0;
            for (var interval : intervals) {
                long start = interval.path("startMs").asLong(), end = interval.path("endMs").asLong();
                String message = video.name() + ": Boss fight #" + (++number) + ": " + timestamp(start)
                        + " -> " + timestamp(end) + " (" + BigDecimal.valueOf(end - start, 3).toPlainString() + " s)"
                        + (interval.path("startClipped").asBoolean() ? " [starts at video boundary]" : "")
                        + (interval.path("endClipped").asBoolean() ? " [continues to video end]" : "");
                intervalLog(job, index, video, message);
            }
            if (number == 0) intervalLog(job, index, video, video.name() + ": No boss fight intervals detected.");
        }
    }

    private void intervalLog(Job job, int index, Video video, String message) {
        LOG.info("{}", message);
        job.emit(mapper.createObjectNode().put("state", "interval").put("operation", "boss-refinement")
                .put("videoIndex", index).put("videoName", video.name()).put("message", message));
    }

    private static String timestamp(long ms) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d",
                ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private void finish(Job job, String state, String message) {
        synchronized (job) {
            job.message = message;
            // Write the terminal event before readers observe a terminal state.
            try {
                job.emit(mapper.createObjectNode().put("state", "job_" + state).put("message", message)
                        .put("resultsAvailable", "completed".equals(state) && job.hasIntervalArtifacts()));
            } finally { job.state = state; }
            try { job.save(); }
            catch (IOException ex) { throw new UncheckedIOException(ex); }
        }
    }

    public synchronized Job get(String id) throws IOException {
        try { UUID.fromString(id); }
        catch (IllegalArgumentException ex) { throw error(HttpStatus.NOT_FOUND, "Job not found."); }
        if (jobs.containsKey(id)) return jobs.get(id);
        Path directory = root.resolve(id);
        if (!Files.isRegularFile(directory.resolve("job.json"))) throw error(HttpStatus.NOT_FOUND, "Job not found.");
        var snapshot = mapper.readTree(Files.readString(directory.resolve("job.json")));
        List<Video> videos = new ArrayList<>();
        for (var item : snapshot.path("videos")) videos.add(mapper.treeToValue(item, Video.class));
        var interval = snapshot.get("samplingIntervalSeconds");
        var job = new Job(id, snapshot.path("gameName").asString(), videos, directory,
                interval == null || interval.isNull() ? null : new BigDecimal(interval.asString()));
        job.state = snapshot.path("state").asString();
        job.message = snapshot.path("message").asString();
        try (var lines = Files.lines(directory.resolve("events.jsonl"))) {
            job.sequence = lines.count();
        }
        if ("running".equals(job.state)) finish(job, "failed", "Controller restarted; processing was interrupted. Completed proxies and frame directories are retained.");
        jobs.put(id, job);
        return job;
    }

    private YoutubeChapters.Result chapters(Video video) throws IOException {
        try {
            return YoutubeChapters.generate(mapper.readTree(Files.readString(
                    Path.of(video.bossRefinementDirectory()).resolve("boss-intervals.json"), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid boss intervals for " + video.name() + ": " + ex.getMessage(), ex);
        }
    }

    public ObjectNode results(String id) throws IOException {
        var job = get(id);
        if (!"completed".equals(job.state)) throw error(HttpStatus.CONFLICT, "Results are available only after successful processing.");
        if (!job.hasIntervalArtifacts()) throw error(HttpStatus.NOT_FOUND, "This job has no saved boss interval results.");
        var response = mapper.createObjectNode().put("jobId", job.id).put("gameName", job.gameName);
        var videos = response.putArray("videos");
        for (int index = 0; index < job.videos.size(); index++) {
            var video = job.videos.get(index);
            var result = (ObjectNode) mapper.valueToTree(chapters(video));
            result.put("videoIndex", index).put("videoName", video.name());
            videos.add(result);
        }
        return response;
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    @PreDestroy
    void shutdown() { workers.shutdownNow(); }
}
