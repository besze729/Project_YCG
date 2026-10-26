package hu.projectycg.controller.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class KnowledgeBaseLookup {
    private final Path root;

    public KnowledgeBaseLookup(@Value("${ycg.knowledge-base.output-root:./data/knowledge-base}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path pathFor(String gameName) {
        String title = gameName.strip();
        if (title.isBlank() || title.length() > 200) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_REQUEST,
                    "gameName must be a non-blank string of at most 200 characters.");
        }
        // Reversible escaping prevents path traversal and collisions with literal escape sequences.
        StringBuilder name = new StringBuilder();
        for (char character : title.toCharArray()) {
            if (character < 32 || "<>:\"/\\|?*%".indexOf(character) >= 0) {
                name.append("%%%04X".formatted((int) character));
            } else name.append(character);
        }
        if (name.toString().matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(\\..*)?")) {
            name.replace(0, 1, "%%%04X".formatted((int) name.charAt(0)));
        }
        if (name.length() > 240 || name.toString().getBytes(StandardCharsets.UTF_8).length > 240) {
            throw new KnowledgeBaseException(KnowledgeBaseException.Code.INVALID_REQUEST,
                    "The game title is too long for a knowledge-base filename. Use a shorter title.");
        }
        return root.resolve(name + ".json");
    }

    public boolean exists(String gameName) {
        Path path = pathFor(gameName);
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Expected a regular JSON file.");
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        } catch (IOException exception) {
            throw storageError(path, exception);
        }
    }

    public void invalidate(String gameName) {
        Path path = pathFor(gameName);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw storageError(path, exception);
        }
    }

    public Path save(String gameName, JsonNode result) {
        Path path = pathFor(gameName);
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, ".knowledge-base-", ".tmp");
            Files.writeString(temporary, result.toPrettyString() + "\n", StandardCharsets.UTF_8);
            // Publish only the complete validated answer; unsupported atomic moves fail explicitly.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return path;
        } catch (IOException exception) {
            throw storageError(path, exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) {
                    org.slf4j.LoggerFactory.getLogger(KnowledgeBaseLookup.class)
                            .warn("Could not remove temporary knowledge-base file {}", temporary, exception);
                }
            }
        }
    }

    private KnowledgeBaseException storageError(Path path, IOException cause) {
        return new KnowledgeBaseException(KnowledgeBaseException.Code.STORAGE_ERROR,
                "Knowledge-base file access failed: " + path, cause);
    }
}
