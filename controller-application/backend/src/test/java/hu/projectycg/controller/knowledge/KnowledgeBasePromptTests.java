package hu.projectycg.controller.knowledge;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBasePromptTests {
    private final KnowledgeBasePrompt prompt = new KnowledgeBasePrompt();

    @Test
    void rendersTheActualResourceWithBothRequiredSubstitutions() {
        assertThat(prompt.render("Mortal Shell"))
                .containsPattern("Game:\\RMortal Shell")
                .containsPattern("Search only:\\Rfextralife\\.com")
                .doesNotContain("<?GAME_TITLE>", "<?SOURCE_SITE>");
    }

    @Test
    void preservesLiteralReplacementCharactersAndDoesNotExpandMarkersInUserInput() {
        String title = "Game $1 \\ <?SOURCE_SITE> \"edition\"";
        assertThat(prompt.substitute("<?GAME_TITLE>\n<?SOURCE_SITE>\n<?GAME_TITLE>", title))
                .isEqualTo(title + "\nfextralife.com\n" + title);
    }

    @Test
    void rejectsMissingAndUnknownTemplateMarkers() {
        assertThatThrownBy(() -> prompt.substitute("<?GAME_TITLE>", "Game"))
                .isInstanceOf(KnowledgeBaseException.class).hasMessageContaining("SOURCE_SITE");
        assertThatThrownBy(() -> prompt.substitute("<?GAME_TITLE><?SOURCE_SITE><?UNKNOWN>", "Game"))
                .isInstanceOf(KnowledgeBaseException.class).hasMessageContaining("UNKNOWN");
    }

}
