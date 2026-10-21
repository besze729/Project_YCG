package hu.projectycg.controller.video;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProxyJobsTests {
    @TempDir Path temp;
    final JsonMapper mapper = JsonMapper.builder().build();
    HttpServer server;
    ProxyJobs jobs;
    VideoProcessingProperties properties;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    volatile String response = "{\"state\":\"started\",\"command\":[\"ffmpeg\",\"-i\",\"original.mp4\"],\"commandLine\":\"ffmpeg -i original.mp4\"}\n"
            + "{\"state\":\"running\",\"fps\":210,\"percent\":50}\n{\"state\":\"completed\"}\n";
    volatile int status = 200;
    volatile long delay;
    java.util.concurrent.ExecutorService executor;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/proxy", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(status, 0);
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        properties = new VideoProcessingProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                temp.resolve("outputs"), Duration.ofSeconds(1), Duration.ofMillis(300));
        jobs = new ProxyJobs(new VideoProcessingClient(properties), properties);
    }

    @AfterEach void stop() { jobs.shutdown(); server.stop(0); executor.shutdownNow(); }

    JsonNode request(String game, Path... sources) {
        var result = mapper.createObjectNode().put("gameName", game);
        var videos = result.putArray("videos");
        for (var source : sources) videos.add(source.toString());
        return result;
    }

    Path source(String name) throws Exception { return Files.writeString(temp.resolve(name), "original media"); }

    void await(ProxyJobs.Job job) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (job.snapshot().path("state").asString().equals("running") && System.nanoTime() < until) Thread.sleep(20);
        assertThat(job.snapshot().path("state").asString()).isNotEqualTo("running");
    }

    @Test void passesOriginalPathsAndGameAndReplaysEvents() throws Exception {
        var first = source("árvíz game 1.mp4");
        var second = source("game 2.mp4");
        var job = jobs.start(request(" Game ", first, second));
        await(job);
        assertThat(job.state).isEqualTo("completed");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("gameName").asString()).isEqualTo("Game");
        assertThat(requests.get(0).path("inputVideo").asString()).isEqualTo(first.toRealPath().toString());
        assertThat(requests.get(1).path("inputVideo").asString()).isEqualTo(second.toRealPath().toString());
        assertThat(Files.readString(first)).isEqualTo("original media");
        var stream = new ByteArrayOutputStream();
        job.stream(1, stream);
        assertThat(stream.toString(StandardCharsets.UTF_8)).contains("commandLine", "210", "job_completed").doesNotContain("job_started");
        var restored = new ProxyJobs(new VideoProcessingClient(properties), properties);
        try { assertThat(restored.get(job.id).snapshot().path("state").asString()).isEqualTo("completed"); }
        finally { restored.shutdown(); }
    }

    @Test void rejectsMissingTitleRelativePathsAndDuplicatesBeforeCallingPython() throws Exception {
        var path = source("game.mp4");
        for (var request : List.of(request(" ", path), request("Game"), request("Game", Path.of("relative.mp4")), request("Game", path, path))) {
            assertThatThrownBy(() -> jobs.start(request)).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test void failedVideoStopsBatchAndReportsReason() throws Exception {
        response = "{\"state\":\"failed\",\"detail\":\"NVENC unavailable\"}\n";
        var job = jobs.start(request("Game", source("one.mp4"), source("two.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("failed");
        assertThat(job.message).contains("NVENC unavailable");
        assertThat(requests).hasSize(1);
    }

    @Test void eofWithoutCompletionFails() throws Exception {
        response = "{\"state\":\"running\",\"fps\":210}\n";
        var job = jobs.start(request("Game", source("one.mp4")));
        await(job);
        assertThat(job.message).contains("before confirming");
    }

    @Test void busyAndTimeoutAreReported() throws Exception {
        delay = 1500;
        var path = source("one.mp4");
        var job = jobs.start(request("Game", path));
        assertThatThrownBy(() -> jobs.start(request("Game", path))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        await(job);
        assertThat(job.state).isEqualTo("failed");
        assertThat(job.message).containsIgnoringCase("timed out");
    }

    @Test void unavailablePythonFailsWithoutFalseCompletion() throws Exception {
        server.stop(0);
        var job = jobs.start(request("Game", source("one.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("failed");
    }
}
