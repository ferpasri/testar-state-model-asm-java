package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.testar.evidence.EvidenceJson;
import static org.testar.asm.Json.*;

/** Download pinned published fixtures into an ignored development cache. */
public final class PrepareConformance {
    static final Path CACHE = Path.of(".cache", "specs");

    private PrepareConformance() {
    }

    public static void main(String[] arguments) throws Exception {
        JsonNode compatibility = EvidenceJson.parse(Files.readString(Path.of("compatibility.json")));
        String url = "https://codeload.github.com/ferpasri/testar-state-model-asm-specs/zip/refs/tags/" +
            text(compatibility, "release");
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "testar-asm-conformance").build();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] archive;
        try (var input = response.body()) {
            archive = input.readNBytes(16 * 1024 * 1024 + 1);
        }
        require(response.statusCode() == 200 && archive.length <= 16 * 1024 * 1024 &&
            digest(archive).equals(text(compatibility, "archive_sha256")),
            "invalid_evidence", "Published archive changed or could not be downloaded.");
        Path destination = CACHE.toAbsolutePath().normalize();
        var hashes = new LinkedHashMap<String, String>();
        int total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String[] parts = entry.getName().split("/", 2);
                if (entry.isDirectory() || parts.length != 2 || !parts[1].startsWith("conformance/")) {
                    continue;
                }
                String name = parts[1];
                Path path = destination.resolve(name).normalize();
                require(path.startsWith(destination) && !name.contains("\\") && !hashes.containsKey(name),
                    "invalid_evidence", "Unsafe archive path.");
                byte[] bytes = zip.readNBytes(64 * 1024 * 1024 - total + 1);
                total += bytes.length;
                require(total <= 64 * 1024 * 1024, "processing_limit", "Expanded archive exceeds 64 MiB.");
                Files.createDirectories(path.getParent());
                Files.write(path, bytes);
                hashes.put(name, digest(bytes));
            }
        }
        Files.writeString(CACHE.resolve("files.json"), object("files", hashes).toPrettyString());
        prepared();
        System.out.println("Prepared shared ASM cases from " + text(compatibility, "release"));
    }

    static Path prepared() throws Exception {
        Path root = CACHE.resolve("conformance");
        require(Files.exists(CACHE.resolve("files.json")), "invalid_evidence",
            "Run the documented prepare-conformance command before testing.");
        JsonNode files = EvidenceJson.parse(Files.readString(CACHE.resolve("files.json"))).get("files");
        var fields = files.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            Path path = CACHE.resolve(field.getKey()).toAbsolutePath().normalize();
            require(path.startsWith(CACHE.toAbsolutePath().normalize()) &&
                digest(Files.readAllBytes(path)).equals(field.getValue().asText()),
                "invalid_evidence", "Cached conformance file changed.");
        }
        JsonNode manifest = EvidenceJson.parse(Files.readString(root.resolve("manifest.json")));
        JsonNode compatibility = EvidenceJson.parse(Files.readString(Path.of("compatibility.json")));
        for (String field : List.of("case_format_version", "asm_contract", "evidence_schema", "rule_language",
                "abstraction_behavior", "asms", "controller")) {
            require(same(manifest.get(field), compatibility.get(field)), "invalid_configuration", "Unsupported shared contract.");
        }
        return root;
    }

    private static String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
