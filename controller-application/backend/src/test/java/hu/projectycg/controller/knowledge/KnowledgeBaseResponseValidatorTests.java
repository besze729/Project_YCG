package hu.projectycg.controller.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseResponseValidatorTests {
    private final KnowledgeBaseResponseValidator validator = new KnowledgeBaseResponseValidator();

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"MAIN_BOSS\"", "\"Unknown category\"", "42", "true", "{}", "[]"})
    void acceptsAnyClassificationValueWithoutCoercion(String classification) {
        String content = """
                {"bosses":[{"displayName":"Grisha","classification":%s}],
                 "sources":["https://mortalshell.wiki.fextralife.com/Grisha"]}
                """.formatted(classification);
        assertThat(validator.validate(content).path("bosses").get(0).path("classification").toString())
                .isEqualTo(classification);
    }

    @Test
    void acceptsEmptyListsWhenNoEncountersWereReturned() {
        assertThat(validator.validate("{\"bosses\":[],\"sources\":[]}").path("bosses")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "not JSON", "null", "[]", "{}",
            "{\"bosses\":[],\"sources\":null}",
            "{\"bosses\":{},\"sources\":[]}",
            "{\"bosses\":[null],\"sources\":[]}",
            "{\"bosses\":[{\"displayName\":\"Boss\"}],\"sources\":[]}",
            "{\"bosses\":[{\"displayName\":\"  \",\"classification\":null}],\"sources\":[]}",
            "{\"bosses\":[{\"displayName\":12,\"classification\":null}],\"sources\":[]}",
            "{\"bosses\":[],\"sources\":[null]}",
            "{\"bosses\":[],\"sources\":[\"not a URL\"]}",
            "{\"bosses\":[],\"sources\":[\"file:///private.txt\"]}",
            "{\"bosses\":[],\"sources\":[]} extra text",
            "{\"bosses\":[],\"sources\":[]} {}",
            "{\"bosses\":[],\"bosses\":[],\"sources\":[]}",
            "```json\n{\"bosses\":[],\"sources\":[]}\n```"
    })
    void rejectsMalformedOrStructurallyInvalidResults(String content) {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOf(KnowledgeBaseException.class)
                .extracting(exception -> ((KnowledgeBaseException) exception).code())
                .isEqualTo(KnowledgeBaseException.Code.INVALID_AI_RESPONSE);
    }
}
