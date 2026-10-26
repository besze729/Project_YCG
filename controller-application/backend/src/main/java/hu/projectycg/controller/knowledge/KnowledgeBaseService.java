package hu.projectycg.controller.knowledge;

import hu.projectycg.controller.lmstudio.LmStudioClient;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    public record BuildResult(String status, String message, String outputPath) {}

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private final KnowledgeBaseLookup lookup;
    private final KnowledgeBasePrompt prompt;
    private final LmStudioClient ai;
    private final KnowledgeBaseResponseValidator validator;
    private final AtomicBoolean building = new AtomicBoolean();

    public KnowledgeBaseService(KnowledgeBaseLookup lookup, KnowledgeBasePrompt prompt,
            LmStudioClient ai, KnowledgeBaseResponseValidator validator) {
        this.lookup = lookup;
        this.prompt = prompt;
        this.ai = ai;
        this.validator = validator;
    }

    public BuildResult build(String gameName, boolean overwrite) {
        if (!building.compareAndSet(false, true)) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.BUSY,
                    "Another knowledge-base build is running. Try again after it finishes.");
        }
        try {
            if (lookup.exists(gameName)) {
                if (!overwrite) return new BuildResult("already_exists", "Knowledge base already exists",
                        lookup.pathFor(gameName).toString());
                lookup.invalidate(gameName);
            }
            LOG.info("Knowledge-base acquisition started.");
            var result = validator.validate(ai.chat(prompt.render(gameName)));
            var path = lookup.save(gameName, result);
            return new BuildResult("completed", "Knowledge base saved successfully.", path.toString());
        } finally {
            building.set(false);
        }
    }
}
