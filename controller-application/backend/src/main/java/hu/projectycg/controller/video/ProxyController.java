package hu.projectycg.controller.video;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/chapters/jobs")
public class ProxyController {
    private final ProxyJobs jobs;
    public ProxyController(ProxyJobs jobs) { this.jobs = jobs; }

    @PostMapping
    public ResponseEntity<JsonNode> start(@RequestBody JsonNode request) throws IOException {
        return ResponseEntity.accepted().body(jobs.start(request).snapshot());
    }

    @GetMapping("/{id}")
    public JsonNode get(@PathVariable String id) throws IOException { return jobs.get(id).snapshot(); }

    @GetMapping(value = "/{id}/events", produces = "text/event-stream")
    public ResponseEntity<StreamingResponseBody> events(@PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long after) throws IOException {
        var job = jobs.get(id);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .body(output -> job.stream(after, output));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("code", "invalid_request", "message", ex.getReason()));
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, String>> ioError(IOException ex) {
        return ResponseEntity.internalServerError().body(Map.of("code", "io_error", "message", "Local file access failed: " + ex.getMessage()));
    }
}
