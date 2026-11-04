package edu.umkc.advisor.retrieval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Maps an ingested source back to the original file in ingest.dir, if one exists. */
@Component
public class SourceFileLocator {
    private final Path dir;

    public SourceFileLocator(@Value("${ingest.dir:../data/imports}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    public Optional<Path> fileFor(SourceRecord source) {
        if (source == null || !Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(path -> matches(source, path)).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean matches(SourceRecord source, Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".")) return false;
        String lower = name.toLowerCase();
        String type = source.type() == null ? "" : source.type();
        if (type.equalsIgnoreCase("PDF") && !lower.endsWith(".pdf")) return false;
        if (type.equalsIgnoreCase("DOCX") && !lower.endsWith(".docx")) return false;
        if (type.equalsIgnoreCase("HTML") || type.equalsIgnoreCase("TEXT")) return false;
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (source.title() != null && source.title().equals(stem)) return true;
        byte[] key = ("file:" + name).getBytes(StandardCharsets.UTF_8);
        return source.id() != null && source.id().equals(UUID.nameUUIDFromBytes(key).toString());
    }
}
