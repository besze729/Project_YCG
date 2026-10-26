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
    public KnowledgeBaseService.BuildResult build(@RequestBody JsonNode request) {
        if (request == null || !request.isObject() || !request.path("gameName").isString()) {
            throw invalidRequest();
        }
        String gameName = request.path("gameName").asString().strip();
        if (gameName.isBlank() || gameName.length() > 200) {
            throw invalidRequest();
        }
        if (request.has("overwrite") && !request.path("overwrite").isBoolean()) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_REQUEST,
                    "overwrite must be a boolean when provided.");
        }
        return service.build(gameName, request.path("overwrite").asBoolean(false));
    }

    private KnowledgeBaseException invalidRequest() {
        return new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_REQUEST,
                "gameName must be a non-blank string of at most 200 characters.");
    }

}
