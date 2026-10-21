package hu.projectycg.controller.knowledge;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping("/build")
    public Result build(@RequestBody JsonNode request) {
        if (request == null || !request.isObject() || !request.path("gameName").isString()) {
            throw invalidRequest();
        }
        String gameName = request.path("gameName").asString().strip();
        if (gameName.isBlank() || gameName.length() > 200) {
            throw invalidRequest();
        }
        if (service.build(gameName) == KnowledgeBaseService.BuildOutcome.ALREADY_EXISTS) {
            return new Result("already_exists", "Knowledge base already exists");
        }
        return new Result("completed", "Knowledge base response validated and printed.");
    }

    private KnowledgeBaseException invalidRequest() {
        return new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_REQUEST,
                "gameName must be a non-blank string of at most 200 characters.");
    }

    public record Result(String status, String message) {}
}
