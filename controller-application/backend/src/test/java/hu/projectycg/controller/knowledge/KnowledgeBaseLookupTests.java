package hu.projectycg.controller.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class KnowledgeBaseLookupTests {
    @TempDir Path temp;

    @Test
    void preservesNormalTitlesAndEscapesUnsafeCharactersWithoutCollisions() {
        var lookup = new KnowledgeBaseLookup(temp);
        assertThat(lookup.pathFor(" Mortal Shell ")).isEqualTo(temp.resolve("Mortal Shell.json"));
        assertThat(lookup.pathFor("Árvíz Game")).isEqualTo(temp.resolve("Árvíz Game.json"));
        for (String title : new String[]{"../Game", "Game: Edition", "CON", "NUL.txt", "A/B", "A\\B", "A%002FB"}) {
            assertThat(lookup.pathFor(title).getParent()).isEqualTo(temp);
            assertThat(lookup.pathFor(title).getFileName().toString()).doesNotContain(":", "/", "\\");
        }
        assertThat(lookup.pathFor("A/B")).isNotEqualTo(lookup.pathFor("A%002FB"));
        assertThat(lookup.pathFor("CON").getFileName().toString()).startsWith("%0043");
        assertThatThrownBy(() -> lookup.pathFor(":".repeat(200))).isInstanceOf(KnowledgeBaseException.class);
    }

    @Test
    void savedJsonSurvivesANewLookupInstance() throws Exception {
        var lookup = new KnowledgeBaseLookup(temp);
        assertThat(lookup.exists("Game")).isFalse();
        var content = new JsonMapper().readTree("{\"bosses\":[],\"sources\":[]}");
        Path saved = lookup.save("Game", content);
        assertThat(saved).isAbsolute();
        assertThat(new KnowledgeBaseLookup(temp).exists("Game")).isTrue();
        assertThat(new JsonMapper().readTree(Files.readString(saved))).isEqualTo(content);
        try (var files = Files.list(temp)) {
            assertThat(files.toList()).containsExactly(saved);
        }
    }
}
