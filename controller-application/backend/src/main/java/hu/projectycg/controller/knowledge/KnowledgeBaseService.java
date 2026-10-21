package hu.projectycg.controller.knowledge;

import hu.projectycg.controller.lmstudio.LmStudioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    public enum BuildOutcome { COMPLETED, ALREADY_EXISTS }

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private final KnowledgeBaseLookup lookup;
    private final KnowledgeBasePrompt prompt;
    private final LmStudioClient ai;
    private final KnowledgeBaseResponseValidator validator;

    public KnowledgeBaseService(KnowledgeBaseLookup lookup, KnowledgeBasePrompt prompt,
            LmStudioClient ai, KnowledgeBaseResponseValidator validator) {
        this.lookup = lookup;
        this.prompt = prompt;
        this.ai = ai;
        this.validator = validator;
    }

    public BuildOutcome build(String gameName) {
        if (lookup.exists(gameName)) {
            return BuildOutcome.ALREADY_EXISTS;
        }
        LOG.info("Knowledge-base acquisition started.");
        var result = validator.validate(ai.chat(prompt.render(gameName)));
        // This increment only prints validated JSON; persistence and further processing are deferred.
        LOG.info("Validated knowledge-base response:\n{}", result.toPrettyString());
        return BuildOutcome.COMPLETED;
    }
}
