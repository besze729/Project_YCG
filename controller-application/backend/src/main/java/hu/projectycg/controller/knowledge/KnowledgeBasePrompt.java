package hu.projectycg.controller.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeBasePrompt {
    private static final Pattern MARKER = Pattern.compile("<\\?([^>]+)>");
    private static final String TEMPLATE = "templates/knowledge_base_aquire_prompt.tpl";

    public String render(String gameName) {
        try {
            return substitute(new ClassPathResource(TEMPLATE).getContentAsString(StandardCharsets.UTF_8), gameName);
        } catch (IOException exception) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.PROMPT_ERROR,
                    "The knowledge-base prompt template could not be read.", exception);
        }
    }

    String substitute(String template, String gameName) {
        Map<String, String> values = Map.of("GAME_TITLE", gameName, "SOURCE_SITE", "fextralife.com");
        for (String name : values.keySet()) {
            if (!template.contains("<?" + name + ">")) {
                throw new KnowledgeBaseException(KnowledgeBaseException.Code.PROMPT_ERROR,
                        "The knowledge-base prompt is missing the required marker: " + name + ".");
            }
        }
        // Replace in one pass so marker-like text in a game name remains literal data.
        return MARKER.matcher(template).replaceAll(match -> {
            String value = values.get(match.group(1));
            if (value == null) {
                throw new KnowledgeBaseException(KnowledgeBaseException.Code.PROMPT_ERROR,
                        "The knowledge-base prompt contains an unknown marker: " + match.group(1) + ".");
            }
            return Matcher.quoteReplacement(value);
        });
    }
}
