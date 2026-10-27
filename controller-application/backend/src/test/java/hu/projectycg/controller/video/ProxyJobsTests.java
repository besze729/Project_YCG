package hu.projectycg.controller.video;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import hu.projectycg.controller.knowledge.KnowledgeBaseLookup;
import hu.projectycg.controller.knowledge.KnowledgeBaseException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    KnowledgeBaseLookup knowledgeBase;
    VideoProcessingProperties properties;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> operations = new CopyOnWriteArrayList<>();
    volatile String response = "{\"state\":\"started\",\"command\":[\"ffmpeg\",\"-i\",\"original.mp4\"],\"commandLine\":\"ffmpeg -i original.mp4\"}\n"
            + "{\"state\":\"running\",\"fps\":210,\"percent\":50}\n{\"state\":\"completed\"}\n";
    volatile int status = 200;
    volatile String sampleResponse = "{\"operation\":\"sample\",\"state\":\"started\",\"commandLine\":\"ffmpeg sample\"}\n"
            + "{\"operation\":\"sample\",\"state\":\"running\",\"frame_count\":1,\"total_frames\":1}\n"
            + "{\"operation\":\"sample\",\"state\":\"completed\",\"frame_count\":1}\n";
    volatile int sampleStatus = 200;
    volatile String detectionResponse = "{\"state\":\"started\",\"model\":\"test-vision\",\"promptSha256\":\"hash\"}\n"
            + "{\"state\":\"running\",\"frame_count\":1,\"total_frames\":1,\"results\":[{\"imageIndex\":0,\"activeBossFight\":true}]}\n"
            + "{\"state\":\"completed\",\"frame_count\":1}\n";
    final CountDownLatch sampleEntered = new CountDownLatch(1);
    volatile CountDownLatch sampleRelease;
    volatile long delay;
    java.util.concurrent.ExecutorService executor;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/", exchange -> {
            String operation = exchange.getRequestURI().getPath().substring("/api/".length());
            operations.add(operation);
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            boolean sample = operation.equals("sample");
            boolean detection = operation.equals("boss-detection");
            String content = detection ? detectionResponse : sample ? sampleResponse : response;
            int code = sample ? sampleStatus : status;
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(code, 0);
            try {
                if (delay > 0) Thread.sleep(delay);
                if (sample) {
                    sampleEntered.countDown();
                    if (sampleRelease != null) sampleRelease.await(5, TimeUnit.SECONDS);
                }
                if (!sample && !detection && code == 200 && content.contains("completed")) {
                    Files.writeString(Path.of(body.path("outputVideo").asString()), "published proxy");
                }
                exchange.getResponseBody().write(content.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        properties = new VideoProcessingProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                temp.resolve("outputs"), Duration.ofSeconds(1), Duration.ofMillis(300), new BigDecimal("30"));
        knowledgeBase = new KnowledgeBaseLookup(temp.resolve("knowledge-base"));
        knowledgeBase.save("Game", mapper.readTree("{\"bosses\":[],\"sources\":[]}"));
        jobs = new ProxyJobs(new VideoProcessingClient(properties), properties, knowledgeBase);
    }

    @AfterEach void stop() {
        if (sampleRelease != null) sampleRelease.countDown();
        jobs.shutdown(); server.stop(0); executor.shutdownNow();
    }

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

    @Test void missingKnowledgeBasePreventsJobCreationAndDownstreamCalls() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ProxyController(jobs)).build();
        mvc.perform(post("/api/chapters/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(request("Missing game", source("one.mp4")).toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("missing_knowledge_base"));
        assertThat(requests).isEmpty();
        assertThat(temp.resolve("outputs")).doesNotExist();
        knowledgeBase.invalidate("Game");
        assertThatThrownBy(() -> jobs.start(request("Game", source("one.mp4"))))
                .isInstanceOf(KnowledgeBaseException.class);
        knowledgeBase.save("Game", mapper.readTree("{\"bosses\":[],\"sources\":[]}"));
        var job = jobs.start(request("Game", source("one.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("completed");
    }

    @Test void passesOriginalPathsAndGameAndReplaysEvents() throws Exception {
        var first = source("árvíz game 1.mp4");
        var second = source("game 2.mp4");
        var job = jobs.start(request(" Game ", first, second));
        await(job);
        assertThat(job.state).isEqualTo("completed");
        assertThat(requests).hasSize(6);
        assertThat(operations).containsExactly("proxy", "sample", "boss-detection", "proxy", "sample", "boss-detection");
        assertThat(requests.get(0).path("gameName").asString()).isEqualTo("Game");
        assertThat(requests.get(0).path("inputVideo").asString()).isEqualTo(first.toRealPath().toString());
        assertThat(requests.get(3).path("inputVideo").asString()).isEqualTo(second.toRealPath().toString());
        for (int i = 0; i < 2; i++) {
            var proxy = requests.get(i * 3);
            var sample = requests.get(i * 3 + 1);
            var detection = requests.get(i * 3 + 2);
            var video = job.snapshot().path("videos").get(i);
            assertThat(sample.path("inputVideo").asString()).isEqualTo(proxy.path("outputVideo").asString());
            assertThat(sample.path("outputDirectory").asString()).isEqualTo(video.path("framesDirectory").asString());
            assertThat(sample.path("gameName").asString()).isEqualTo("Game");
            assertThat(sample.path("intervalSeconds").asInt()).isEqualTo(30);
            assertThat(detection.path("gameName").asString()).isEqualTo("Game");
            assertThat(detection.path("framesDirectory").asString()).isEqualTo(video.path("framesDirectory").asString());
            assertThat(detection.path("outputFile").asString()).isEqualTo(video.path("bossDetectionsPath").asString());
        }
        assertThat(Files.readString(first)).isEqualTo("original media");
        var stream = new ByteArrayOutputStream();
        job.stream(1, stream);
        assertThat(stream.toString(StandardCharsets.UTF_8)).contains("commandLine", "210", "job_completed", "sample", "total_frames", "boss-detection", "activeBossFight", "promptSha256").doesNotContain("job_started");
        var restored = new ProxyJobs(new VideoProcessingClient(properties), properties, knowledgeBase);
        try { assertThat(restored.get(job.id).snapshot()).isEqualTo(job.snapshot()); }
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

    @Test void jobStaysRunningUntilSamplingCompletes() throws Exception {
        sampleRelease = new CountDownLatch(1);
        var path = source("one.mp4");
        var job = jobs.start(request("Game", path));
        try {
            assertThat(sampleEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(job.state).isEqualTo("running");
            assertThatThrownBy(() -> jobs.start(request("Game", path)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        } finally { sampleRelease.countDown(); }
        await(job);
        assertThat(job.state).isEqualTo("completed");
    }

    @Test void samplingFailureRetainsProxyAndStopsBeforeNextVideo() throws Exception {
        sampleResponse = "{\"state\":\"failed\",\"detail\":\"JPEG write failed\"}\n";
        var job = jobs.start(request("Game", source("one.mp4"), source("two.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("failed");
        assertThat(job.message).contains("one.mp4", "frame sampling", "JPEG write failed");
        assertThat(operations).containsExactly("proxy", "sample");
        assertThat(Path.of(job.videos.getFirst().outputPath())).hasContent("published proxy");
        assertThat(Path.of(job.videos.getFirst().framesDirectory())).doesNotExist();
        assertThat(Files.readString(job.directory.resolve("events.jsonl"))).contains("job_failed").doesNotContain("job_completed");
    }

    @Test void samplingEofWithoutCompletionFails() throws Exception {
        sampleResponse = "{\"state\":\"running\",\"frame_count\":1}\n";
        var job = jobs.start(request("Game", source("one.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("failed");
        assertThat(job.message).contains("before confirming sample completion");
    }

    @Test void detectionFailureStopsBeforeNextVideo() throws Exception {
        detectionResponse = "{\"state\":\"failed\",\"detail\":\"Invalid LM Studio detection response\"}\n";
        var job = jobs.start(request("Game", source("one.mp4"), source("two.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("failed");
        assertThat(job.message).contains("one.mp4", "boss fight detection", "Invalid LM Studio");
        assertThat(operations).containsExactly("proxy", "sample", "boss-detection");
        assertThat(Files.readString(job.directory.resolve("events.jsonl"))).contains("job_failed").doesNotContain("job_completed");
    }

    @Test void detectionEofWithoutCompletionFails() throws Exception {
        detectionResponse = "{\"state\":\"running\",\"frame_count\":1}\n";
        var job = jobs.start(request("Game", source("one.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("failed");
        assertThat(job.message).contains("before confirming boss-detection completion");
    }

    @Test void samplingHttpErrorFailsWithStageAndReason() throws Exception {
        sampleStatus = 409;
        sampleResponse = "{\"detail\":\"Output already exists.\"}";
        var job = jobs.start(request("Game", source("one.mp4")));
        await(job);
        assertThat(job.state).isEqualTo("failed");
        assertThat(job.message).contains("frame sampling", "HTTP 409", "Output already exists");
    }

    @Test void configuredFractionalIntervalIsSentAndPersisted() throws Exception {
        jobs.shutdown();
        var configured = new VideoProcessingProperties(properties.baseUrl(), properties.outputRoot(),
                properties.connectTimeout(), properties.idleTimeout(), new BigDecimal("0.5"));
        jobs = new ProxyJobs(new VideoProcessingClient(configured), configured, knowledgeBase);
        var job = jobs.start(request("Game", source("one.mp4")));
        await(job);
        assertThat(requests.get(1).path("intervalSeconds").asDouble()).isEqualTo(0.5);
        var persisted = mapper.readTree(Files.readString(job.directory.resolve("job.json")));
        assertThat(persisted.path("samplingIntervalSeconds").asDouble()).isEqualTo(0.5);
    }

    @Test void invalidSamplingConfigurationFailsEarly() {
        for (var interval : List.of("0", "-1", "0.0001")) {
            assertThatThrownBy(() -> new VideoProcessingProperties(properties.baseUrl(), properties.outputRoot(),
                    properties.connectTimeout(), properties.idleTimeout(), new BigDecimal(interval)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Sampling interval");
        }
        assertThatThrownBy(() -> new VideoProcessingProperties(properties.baseUrl(), properties.outputRoot(),
                properties.connectTimeout(), properties.idleTimeout(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Sampling interval");
    }

    @Test void legacyProxyOnlyJobsRemainReadableWithoutStartingSampling() throws Exception {
        String id = UUID.randomUUID().toString();
        var directory = Files.createDirectories(properties.outputRoot().resolve(id));
        var snapshot = mapper.createObjectNode().put("jobId", id).put("gameName", "Game")
                .put("state", "completed").put("message", "All proxy videos are ready.");
        snapshot.putArray("videos").addObject().put("name", "old.mp4").put("inputPath", "old.mp4")
                .put("outputPath", "0001-proxy.mp4");
        Files.writeString(directory.resolve("job.json"), mapper.writeValueAsString(snapshot));
        Files.writeString(directory.resolve("events.jsonl"), "{\"id\":1,\"state\":\"job_completed\"}\n");
        var restored = jobs.get(id).snapshot();
        assertThat(restored.path("state").asString()).isEqualTo("completed");
        assertThat(restored.path("videos").get(0).path("framesDirectory").isNull()).isTrue();
        assertThat(restored.path("videos").get(0).path("bossDetectionsPath").isNull()).isTrue();
        assertThat(restored.path("samplingIntervalSeconds").isNull()).isTrue();
        assertThat(requests).isEmpty();
    }
}
