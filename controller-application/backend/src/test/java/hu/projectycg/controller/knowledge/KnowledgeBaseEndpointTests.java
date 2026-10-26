package hu.projectycg.controller.knowledge;

import com.sun.net.httpserver.HttpServer;
import hu.projectycg.controller.lmstudio.LmStudioClient;
import hu.projectycg.controller.lmstudio.LmStudioProperties;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class KnowledgeBaseEndpointTests {
    @TempDir Path temp;
    private KnowledgeBaseLookup lookup;
    private static final String PATH = "/api/knowledge-base/build";
    private final JsonMapper mapper = new JsonMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private MockMvc mvc;
    private URI baseUrl;
    private volatile int upstreamStatus = 200;
    private volatile String upstreamBody;
    private volatile long delayMillis;
    private volatile CountDownLatch responseRelease;
    private final CountDownLatch upstreamEntered = new CountDownLatch(1);
    private volatile JsonNode receivedBody;
    private volatile String receivedAuthorization;
    private volatile String receivedAccept;
    private volatile String receivedMethod;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        upstreamBody = envelope("{\"bosses\":[{\"displayName\":\"Grisha\",\"classification\":null}],\"sources\":[]}");
        server.createContext("/api/v1/chat", exchange -> {
            calls.incrementAndGet();
            receivedMethod = exchange.getRequestMethod();
            receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedAccept = exchange.getRequestHeaders().getFirst("Accept");
            receivedBody = mapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                upstreamEntered.countDown();
                if (responseRelease != null) responseRelease.await(5, TimeUnit.SECONDS);
                Thread.sleep(delayMillis);
                byte[] bytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(upstreamStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        lookup = new KnowledgeBaseLookup(temp.resolve("knowledge-base"));
        mvc = mvc(lookup, Duration.ofSeconds(3));
    }

    @AfterEach
    void tearDown() {
        if (responseRelease != null) responseRelease.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private MockMvc mvc(KnowledgeBaseLookup lookup, Duration timeout) {
        var properties = new LmStudioProperties(baseUrl, "test-model", Duration.ofSeconds(1),
                timeout, "test-token", "mcp/test-search");
        var service = new KnowledgeBaseService(lookup, new KnowledgeBasePrompt(),
                new LmStudioClient(properties), new KnowledgeBaseResponseValidator());
        return MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(service))
                .setControllerAdvice(new KnowledgeBaseErrorHandler()).build();
    }

    private String envelope(String content) {
        var root = mapper.createObjectNode();
        root.putArray("output").addObject().put("type", "message").put("content", content);
        return stream(root.toString());
    }

    private String stream(String result) {
        return "event: chat.start\ndata: {\"type\":\"chat.start\"}\n\n"
                + "event: chat.end\ndata: {\"type\":\"chat.end\",\"result\":" + result + "}\n\n";
    }

    @Test
    void sendsTheRenderedPromptAndSavesOnlyValidatedJson(CapturedOutput output) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\" Mortal Shell \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.outputPath").value(lookup.pathFor("Mortal Shell").toString()));
        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedAuthorization).isEqualTo("Bearer test-token");
        assertThat(receivedAccept).isEqualTo("text/event-stream");
        assertThat(receivedBody.path("model").asString()).isEqualTo("test-model");
        assertThat(receivedBody.path("input").asString()).contains("Mortal Shell", "fextralife.com")
                .doesNotContain("<?GAME_TITLE>", "<?SOURCE_SITE>");
        assertThat(receivedBody.path("reasoning").asString()).isEqualTo("off");
        assertThat(receivedBody.path("stream").asBoolean()).isTrue();
        assertThat(receivedBody.path("store").asBoolean()).isFalse();
        assertThat(receivedBody.path("integrations").get(0).asString()).isEqualTo("mcp/test-search");
        assertThat(Files.readString(lookup.pathFor("Mortal Shell"))).contains("\"displayName\" : \"Grisha\"");
        assertThat(output).doesNotContain("Validated knowledge-base response:", "\"displayName\" : \"Grisha\"");
    }

    @Test
    void returnsAlreadyExistsWithoutCallingTheModel(CapturedOutput output) throws Exception {
        lookup.save("Mortal Shell", mapper.readTree("{\"bosses\":[],\"sources\":[]}"));
        String previous = Files.readString(lookup.pathFor("Mortal Shell"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Mortal Shell\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_exists"))
                .andExpect(jsonPath("$.message").value("Knowledge base already exists"));
        assertThat(calls.get()).isZero();
        assertThat(Files.readString(lookup.pathFor("Mortal Shell"))).isEqualTo(previous);
        assertThat(output).doesNotContain("Validated knowledge-base response:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]", "{}", "{\"gameName\":null}",
            "{\"gameName\":12}", "{\"gameName\":\"\"}", "{\"gameName\":\"   \"}"})
    void rejectsInvalidRequestsWithoutCallingTheModel(String content) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectsOverlongGameNames() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode().put("gameName", "x".repeat(201)).toString()))
                .andExpect(status().isBadRequest());
        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectsAnUnsupportedRequestContentType() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content("Mortal Shell"))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(calls.get()).isZero();
    }

    @Test
    void takesTheFinalMessageAndIgnoresReasoningAndToolOutput() throws Exception {
        var root = mapper.createObjectNode();
        var items = root.putArray("output");
        items.addObject().put("type", "reasoning").put("content", "private reasoning, not JSON");
        items.addObject().put("type", "message").put("content", "Searching...");
        items.addObject().put("type", "tool_call").put("output", "tool result, not JSON");
        items.addObject().put("type", "message").put("content", "{\"bosses\":[],\"sources\":[]}");
        upstreamBody = "event: message.delta\ndata: {\"type\":\"message.delta\",\"content\":\"invalid partial JSON\"}\n\n"
                + stream(root.toString());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAStreamWithoutChatEnd() throws Exception {
        upstreamBody = "event: message.delta\ndata: {\"type\":\"message.delta\",\"content\":\"partial\"}\n\n";
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("invalid_ai_response"));
    }

    @Test
    void rejectsAnErrorEvenWhenAChatEndFollows() throws Exception {
        upstreamBody = "event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"SEARCH_SECRET\"}}\n\n"
                + envelope("{\"bosses\":[],\"sources\":[]}");
        var result = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("invalid_ai_response"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SEARCH_SECRET");
    }

    @Test
    void rejectsMalformedChatEndEvent() throws Exception {
        upstreamBody = "event: chat.end\ndata: {\"type\":\"chat.end\",\"result\":\n\n";
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("invalid_ai_response"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON", "null", "{}", "{\"output\":[]}", "{\"output\":[{}]}",
            "{\"output\":[{\"type\":\"message\",\"content\":12}]}",
            "{\"output\":[{\"type\":\"reasoning\",\"content\":\"thinking\"}]}",
            "{\"output\":[{\"type\":\"message\",\"content\":\"\"}]}"})
    void rejectsInvalidNativeChatEnvelopes(String body) throws Exception {
        upstreamBody = stream(body);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("invalid_ai_response"));
    }

    @Test
    void doesNotPrintAnInvalidModelResult(CapturedOutput output) throws Exception {
        upstreamBody = envelope("{\"bosses\":\"INVALID_RESULT_SENTINEL\",\"sources\":[]}");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("invalid_ai_response"));
        assertThat(output).doesNotContain("Validated knowledge-base response:", "INVALID_RESULT_SENTINEL");
    }

    @Test
    void mapsUpstreamHttpErrorsWithoutLeakingTheirBodies() throws Exception {
        upstreamStatus = 401;
        upstreamBody = "{\"error\":\"SECRET_SENTINEL\"}";
        var result = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ai_http_error")).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("HTTP 401", "LM_STUDIO_API_TOKEN")
                .doesNotContain("SECRET_SENTINEL");
    }

    @Test
    void reportsAnUnavailableLocalServer() throws Exception {
        server.stop(0);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_unavailable"));
    }

    @Test
    void explainsTheMcpPermissionRequiredAfterAForbiddenResponse() throws Exception {
        upstreamStatus = 403;
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(
                        "LM Studio returned HTTP 403. Allow the configured MCP server for API use in LM Studio and check API token permissions."));
    }

    @Test
    void reportsARequestTimeout() throws Exception {
        delayMillis = 1000;
        mvc = mvc(lookup, Duration.ofMillis(100));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("ai_timeout"));
    }

    @Test
    void confirmedRebuildReplacesThePreviousFile() throws Exception {
        lookup.save("Mortal Shell", mapper.readTree("{\"bosses\":[],\"sources\":[]}"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameName\":\"Mortal Shell\",\"overwrite\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(Files.readString(lookup.pathFor("Mortal Shell"))).contains("Grisha");
        try (var files = Files.list(temp.resolve("knowledge-base"))) {
            assertThat(files.toList()).containsExactly(lookup.pathFor("Mortal Shell"));
        }
    }

    @Test
    void failedRebuildInvalidatesTheOldKnowledgeBase() throws Exception {
        lookup.save("Game", mapper.readTree("{\"bosses\":[],\"sources\":[]}"));
        upstreamBody = envelope("invalid");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameName\":\"Game\",\"overwrite\":true}"))
                .andExpect(status().isBadGateway());
        assertThat(lookup.exists("Game")).isFalse();
        upstreamBody = envelope("{\"bosses\":[],\"sources\":[]}");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isOk());
        assertThat(lookup.exists("Game")).isTrue();
    }

    @Test
    void reportsStorageFailureInsteadOfSuccess() throws Exception {
        Files.writeString(temp.resolve("knowledge-base"), "not a directory");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("storage_error"));
    }

    @Test
    void rebuildingIsUnavailableUntilPublishedAndRejectsConcurrentBuilds() throws Exception {
        lookup.save("Game", mapper.readTree("{\"bosses\":[],\"sources\":[]}"));
        responseRelease = new CountDownLatch(1);
        var pending = executor.submit(() -> mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gameName\":\"Game\",\"overwrite\":true}")).andReturn());
        assertThat(upstreamEntered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(lookup.exists("Game")).isFalse();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"Game\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("busy"));
        responseRelease.countDown();
        assertThat(pending.get(3, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(lookup.exists("Game")).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"true\"", "1", "{}"})
    void rejectsNonBooleanOverwrite(String overwrite) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameName\":\"Game\",\"overwrite\":" + overwrite + "}"))
                .andExpect(status().isBadRequest());
        assertThat(calls.get()).isZero();
    }
}
