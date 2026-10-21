package hu.projectycg.controller.video;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
    private final VideoProcessingClient client;
    private final Path root;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ProxyJobs(VideoProcessingClient client, VideoProcessingProperties properties) {
        this.client = client;
        root = properties.outputRoot().toAbsolutePath().normalize();
    }

    public record Video(String name, String inputPath, String outputPath) {}

    public final class Job {
        final String id;
        final String gameName;
        final List<Video> videos;
        final Path directory;
        volatile String state = "running";
        volatile String message = "Proxy processing started.";
        long sequence;

        Job(String id, String gameName, List<Video> videos, Path directory) {
            this.id = id; this.gameName = gameName; this.videos = videos; this.directory = directory;
        }

        public synchronized ObjectNode snapshot() {
            var result = mapper.createObjectNode().put("jobId", id).put("gameName", gameName)
                    .put("state", state).put("message", message);
            result.set("videos", mapper.valueToTree(videos));
            return result;
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
        if (!busy.compareAndSet(false, true)) throw error(HttpStatus.CONFLICT, "Another proxy batch is running.");
        try {
            String id = UUID.randomUUID().toString();
            Path directory = Files.createDirectories(root.resolve(id));
            var videos = new ArrayList<Video>();
            for (int i = 0; i < sources.size(); i++) {
                Path source = sources.get(i);
                videos.add(new Video(source.getFileName().toString(), source.toString(),
                        directory.resolve(String.format("%04d-proxy.mp4", i + 1)).toString()));
            }
            var job = new Job(id, request.path("gameName").asString().strip(), List.copyOf(videos), directory);
            job.save();
            job.emit(mapper.createObjectNode().put("state", "job_started").put("message", job.message));
            jobs.put(id, job);
            workers.submit(() -> process(job));
            return job;
        } catch (Exception ex) { busy.set(false); throw ex; }
    }

    private void process(Job job) {
        try {
            for (int index = 0; index < job.videos.size(); index++) {
                int videoIndex = index;
                var video = job.videos.get(index);
                client.createProxy(job.gameName, Path.of(video.inputPath()), Path.of(video.outputPath()), event -> {
                    var enriched = ((ObjectNode) event).deepCopy();
                    enriched.put("videoIndex", videoIndex).put("videoName", video.name());
                    enriched.put("message", video.name() + ": " + event.path("state").asString());
                    job.emit(enriched);
                });
            }
            finish(job, "completed", "All proxy videos are ready.");
        } catch (Exception ex) {
            finish(job, "failed", "Proxy processing failed: " + ex.getMessage());
        } finally { busy.set(false); }
    }

    private void finish(Job job, String state, String message) {
        synchronized (job) {
            job.message = message;
            // Write the terminal event before readers observe a terminal state.
            try {
                job.emit(mapper.createObjectNode().put("state", "job_" + state).put("message", message));
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
        var job = new Job(id, snapshot.path("gameName").asString(), videos, directory);
        job.state = snapshot.path("state").asString();
        job.message = snapshot.path("message").asString();
        try (var lines = Files.lines(directory.resolve("events.jsonl"))) {
            job.sequence = lines.count();
        }
        if ("running".equals(job.state)) finish(job, "failed", "Controller restarted; processing was interrupted. Completed proxy files are retained.");
        jobs.put(id, job);
        return job;
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    @PreDestroy
    void shutdown() { workers.shutdownNow(); }
}
