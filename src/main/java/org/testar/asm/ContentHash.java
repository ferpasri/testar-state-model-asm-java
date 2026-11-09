package org.testar.asm;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Calculate compact content hashes for retry checks and evidence source identification. */
final class ContentHash {
    private ContentHash() {
    }

    /** Hash the same JSON text previously materialized for an evidence source. */
    static String source(JsonNode value) {
        return digest(value, false);
    }

    /** Compare numeric values consistently: 1 and 1.0 match, while true and 1 differ. */
    static String canonical(JsonNode value) {
        return digest(value, true);
    }

    private static String digest(JsonNode value, boolean canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
                    var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                if (canonical) {
                    try (JsonGenerator generator = Json.MAPPER.createGenerator(writer)) {
                        writeCanonical(generator, value);
                    }
                } else {
                    Json.MAPPER.writeValue(writer, value);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot calculate content hash", error);
        }
    }

    private static void writeCanonical(JsonGenerator output, JsonNode value) throws java.io.IOException {
        if (value.isObject()) {
            output.writeStartObject();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                output.writeFieldName(name);
                writeCanonical(output, value.get(name));
            }
            output.writeEndObject();
        } else if (value.isArray()) {
            output.writeStartArray();
            for (JsonNode item : value) {
                writeCanonical(output, item);
            }
            output.writeEndArray();
        } else if (value.isNumber()) {
            BigDecimal number = value.decimalValue();
            output.writeNumber(number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros());
        } else {
            output.writeTree(value);
        }
    }
}
