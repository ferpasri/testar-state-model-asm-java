package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testar.evidence.EvidenceJson;
import static org.testar.asm.Json.*;

/** Own new input data and reuse accepted states only after comparing their actual content. */
final class PreparedSnapshot {
    record RecordKey(String source, String collection, String id) {
    }
    record RecordContent(JsonNode data, String contentHash) {
    }
    private record Reused(int source, String collection, int index, JsonNode data) {
    }

    JsonNode snapshot;
    final Map<RecordKey, RecordContent> records = new HashMap<>();
    final String contentHash;

    PreparedSnapshot(JsonNode sources, JsonNode selectedRuns, StateModelData previous) {
        try {
            Map<RecordKey, RecordContent> accepted = previous == null ? Map.of() : previous.records;
            ArrayNode inputSources = MAPPER.createArrayNode();
            List<Reused> reused = new ArrayList<>();
            for (JsonNode source : list(sources)) {
                ObjectNode input = MAPPER.createObjectNode();
                input.setAll((ObjectNode) source);
                ObjectNode evidence = MAPPER.createObjectNode();
                var collections = source.get("evidence").fields();
                while (collections.hasNext()) {
                    var collection = collections.next();
                    ArrayNode values = MAPPER.createArrayNode();
                    for (JsonNode value : list(collection.getValue())) {
                        RecordKey key = new RecordKey(text(source, "id"), collection.getKey(), recordId(collection.getKey(), value));
                        RecordContent old = accepted.get(key);
                        if (old != null && same(old.data(), value)) {
                            // Placeholders preserve the original JSON nesting context while
                            // keeping already accepted widgets out of conversion and parsing.
                            reused.add(new Reused(inputSources.size(), collection.getKey(), values.size(), old.data()));
                            values.addNull();
                        } else {
                            values.add(value);
                        }
                    }
                    evidence.set(collection.getKey(), values);
                }
                input.set("evidence", evidence);
                inputSources.add(input);
            }
            ObjectNode input = MAPPER.createObjectNode();
            input.set("sources", inputSources);
            input.set("selected_runs", selectedRuns);
            snapshot = EvidenceJson.tree(input);
            for (Reused item : reused) {
                ((ArrayNode) snapshot.get("sources").get(item.source()).get("evidence").get(item.collection()))
                    .set(item.index(), item.data());
            }
            List<JsonNode> orderedSources = list(snapshot.get("sources"));
            orderedSources.sort(Comparator.comparing(item -> text(item, "id"), TEXT));
            ArrayNode signatures = MAPPER.createArrayNode();
            for (JsonNode source : orderedSources) {
                ObjectNode signature = MAPPER.createObjectNode();
                source.fields().forEachRemaining(entry -> {
                    if (!entry.getKey().equals("evidence")) {
                        signature.set(entry.getKey(), entry.getValue());
                    }
                });
                ObjectNode collectionHashes = MAPPER.createObjectNode();
                var collections = source.get("evidence").fields();
                while (collections.hasNext()) {
                    var collection = collections.next();
                    List<JsonNode> values = list(collection.getValue());
                    values.sort(Comparator.comparing(value -> recordId(collection.getKey(), value), TEXT));
                    ArrayNode ordered = MAPPER.createArrayNode();
                    ArrayNode hashes = MAPPER.createArrayNode();
                    for (JsonNode value : values) {
                        RecordKey key = new RecordKey(text(source, "id"), collection.getKey(), recordId(collection.getKey(), value));
                        RecordContent old = accepted.get(key);
                        RecordContent content = old != null && old.data() == value ? old : new RecordContent(value, ContentHash.canonical(value));
                        records.put(key, content);
                        ordered.add(content.data());
                        hashes.add(content.contentHash());
                    }
                    ((ObjectNode) source.get("evidence")).set(collection.getKey(), ordered);
                    collectionHashes.set(collection.getKey(), hashes);
                }
                signature.set("evidence", collectionHashes);
                signatures.add(signature);
            }
            List<JsonNode> runs = list(snapshot.get("selected_runs"));
            runs.sort(Comparator.comparing(value -> List.of(text(value, "source"), text(value, "run")), KEYS));
            ((ObjectNode) snapshot).set("sources", MAPPER.createArrayNode().addAll(orderedSources));
            ((ObjectNode) snapshot).set("selected_runs", MAPPER.createArrayNode().addAll(runs));
            ObjectNode signature = MAPPER.createObjectNode();
            signature.set("sources", signatures);
            signature.set("selected_runs", snapshot.get("selected_runs"));
            contentHash = ContentHash.canonical(signature);
        } catch (AsmException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AsmException("invalid_evidence", error.getMessage());
        }
    }

    private static String recordId(String collection, JsonNode record) {
        return text(collection.equals("executions") ? record.get("execution") : record, "id");
    }
}
