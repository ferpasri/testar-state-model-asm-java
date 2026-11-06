package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.JsonlStore;
import org.testar.evidence.Manifest;
import static org.junit.jupiter.api.Assertions.*;
import static org.testar.asm.Json.*;

final class InputBoundaryTest {
    @Test
    void equivalentNumericValuesDoNotAdvanceStateModelData() throws Exception {
        JsonNode data = Examples.input("empty-start");
        JsonNode snapshot = data.get("evidence_sets").get("observed");
        ObjectNode properties = (ObjectNode) snapshot.get("sources").get(0).get("evidence").get("observations")
            .get(0).get("recorded_state").get("properties");
        properties.put("example.number", 1);
        AsmRun run = new AsmRun(data.get("configuration"), snapshot.get("sources"), snapshot.get("selected_runs"));
        properties.put("example.number", new BigDecimal("1.0"));
        JsonNode result = run.updateEvidence("same", snapshot.get("sources"), snapshot.get("selected_runs"));
        assertFalse(result.get("changed").asBoolean());
        assertEquals(0, run.asmDataRevision());
    }

    @Test
    void duplicateSourceUpdatesAreRejectedWithoutChangingTheModel() throws Exception {
        JsonNode data = Examples.input("empty-start");
        JsonNode snapshot = data.get("evidence_sets").get("observed");
        AsmRun run = new AsmRun(data.get("configuration"), snapshot.get("sources"), snapshot.get("selected_runs"));
        JsonNode before = run.model();
        JsonNode source = snapshot.get("sources").get(0);
        AsmException error = assertThrows(AsmException.class, () ->
            run.updateEvidence("duplicate", array(List.of(source, source)), snapshot.get("selected_runs")));
        assertEquals("invalid_evidence", error.code());
        assertEquals(before, run.model());
        assertEquals(0, run.asmDataRevision());
    }

    @Test
    void returnedModelAndCallerConfigurationAreIndependent() throws Exception {
        JsonNode data = Examples.input("empty-start");
        JsonNode snapshot = data.get("evidence_sets").get("observed");
        AsmRun run = new AsmRun(data.get("configuration"), snapshot.get("sources"), snapshot.get("selected_runs"), size -> 0);
        ((ObjectNode) data.get("configuration").get("asms").get(0)).put("name", "invalid");
        ((com.fasterxml.jackson.databind.node.ArrayNode) run.model().get("nodes")).removeAll();
        run.setContext(data.get("steps").get(2).get("context"));
        assertEquals("selected", run.select().get("result_type").asText());
        assertFalse(run.model().get("nodes").isEmpty());
    }

    @Test
    void realEvidenceFilesCanBeLoadedForSelection(@TempDir Path temporary) throws Exception {
        JsonNode data = Examples.input("empty-start");
        JsonNode original = data.get("evidence_sets").get("observed").get("sources").get(0).get("evidence");
        Path directory = temporary.resolve("trace");
        try (JsonlStore store = JsonlStore.create(directory, new Manifest("trace"))) {
            var events = new java.util.ArrayList<JsonNode>();
            for (String collection : List.of("runs", "sequences", "actions", "observations")) {
                String type = switch (collection) {
                    case "runs" -> "test_run_started";
                    case "sequences" -> "test_sequence_started";
                    case "actions" -> "action_described";
                    default -> "observation_recorded";
                };
                for (JsonNode value : original.get(collection)) {
                    ObjectNode record = value.deepCopy();
                    if (collection.equals("runs") || collection.equals("sequences")) {
                        record.remove(List.of("status", "reason"));
                    }
                    events.add(object("schema_version", 3, "id", Integer.toString(events.size()),
                        "event", object("event_type", type, "data", record)));
                }
            }
            for (JsonNode event : events) {
                store.record(EvidenceJson.readEvent(event.toString()));
            }
        }
        JsonNode source = EvidenceSources.read(directory, "live");
        AsmRun run = new AsmRun(data.get("configuration"), array(List.of(source)),
            array(List.of(object("source", "live", "run", "run"))), size -> 0);
        run.setContext(data.get("steps").get(2).get("context"));
        assertEquals("local_unvisited_action", run.select().get("reason").asText());
    }
    @Test
    void finishingTheCurrentSequenceRetiresItsSelectionContext() throws Exception {
        JsonNode data = Examples.input("empty-start");
        JsonNode snapshot = data.get("evidence_sets").get("observed");
        AsmRun run = new AsmRun(data.get("configuration"), snapshot.get("sources"), snapshot.get("selected_runs"), size -> 0);
        run.setContext(data.get("steps").get(2).get("context"));
        JsonNode selected = run.select();
        ObjectNode sequence = (ObjectNode) snapshot.get("sources").get(0).get("evidence").get("sequences").get(0);
        sequence.put("status", "finished");
        sequence.put("reason", "Sequence ended");
        run.updateEvidence("finished", snapshot.get("sources"), snapshot.get("selected_runs"));
        assertEquals("no_context", run.select().get("reason").asText());
        AsmException error = assertThrows(AsmException.class, () -> run.beginExecution(selected,
            object("source", "live", "run", "run", "execution", "too-late")));
        assertEquals("stale_selection", error.code());
    }

}
