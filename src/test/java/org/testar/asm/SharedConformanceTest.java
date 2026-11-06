package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.testar.evidence.EvidenceJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.testar.asm.Json.*;

final class SharedConformanceTest {
    static final class RandomChoices implements IntUnaryOperator {
        private final ArrayDeque<JsonNode> draws = new ArrayDeque<>();

        void set(JsonNode values) {
            assertTrue(draws.isEmpty(), "Previous random choices were not consumed.");
            if (values != null) {
                values.forEach(draws::add);
            }
        }

        void finished() {
            assertTrue(draws.isEmpty(), "Expected random choices were not consumed.");
        }

        @Override
        public int applyAsInt(int size) {
            assertFalse(draws.isEmpty(), "Unexpected random draw.");
            JsonNode draw = draws.removeFirst();
            assertEquals(draw.path("size").asInt(), size, "Random choice size.");
            return draw.path("index").asInt();
        }
    }

    @TestFactory
    Stream<DynamicTest> publishedCases() throws Exception {
        var root = PrepareConformance.prepared();
        JsonNode manifest = EvidenceJson.parse(Files.readString(root.resolve("manifest.json")));
        var tests = new ArrayList<DynamicTest>();
        for (JsonNode name : manifest.get("suites").get("selection").get("cases")) {
            var path = root.resolve("cases").resolve(name.asText());
            JsonNode data = EvidenceJson.parse(Files.readString(path.resolve("input.json")));
            JsonNode expected = EvidenceJson.parse(Files.readString(path.resolve("expected.json")));
            for (boolean reverse : List.of(false, true)) {
                tests.add(DynamicTest.dynamicTest(name.asText() + (reverse ? " / reversed" : ""), () ->
                    verify(reverse ? reversed(data) : data.deepCopy(), expected)));
            }
        }
        return tests.stream();
    }

    private static JsonNode reversed(JsonNode value) {
        ObjectNode data = value.deepCopy();
        for (JsonNode snapshot : data.get("evidence_sets")) {
            for (String field : List.of("sources", "selected_runs")) {
                var values = list(snapshot.get(field));
                Collections.reverse(values);
                ((ObjectNode) snapshot).set(field, array(values));
            }
            for (JsonNode source : snapshot.get("sources")) {
                var fields = new ArrayList<String>();
                source.get("evidence").fieldNames().forEachRemaining(fields::add);
                for (String field : fields) {
                    var values = list(source.get("evidence").get(field));
                    Collections.reverse(values);
                    ((ObjectNode) source.get("evidence")).set(field, array(values));
                }
            }
        }
        for (JsonNode step : data.get("steps")) {
            if (text(step, "operation").equals("set_context")) {
                var choices = list(step.get("context").get("choices"));
                Collections.reverse(choices);
                ((ObjectNode) step.get("context")).set("choices", array(choices));
            }
        }
        return data;
    }

    static void verify(JsonNode data, JsonNode expected) {
        JsonNode before = data.deepCopy();
        RandomChoices random = new RandomChoices();
        AsmRun run;
        JsonNode initial = data.get("evidence_sets").get(text(data, "initial_evidence"));
        try {
            run = new AsmRun(data.get("configuration"), initial.get("sources"), initial.get("selected_runs"), random);
            assertEquals(expected.get("initialization"), object("result_type", "ready", "asm_data_revision", 0));
        } catch (AsmException error) {
            assertEquals(expected.get("initialization"),
                object("result_type", "error", "asm_data_revision", 0, "code", error.code()));
            return;
        }
        for (int index = 0; index < data.get("steps").size(); index++) {
            JsonNode step = data.get("steps").get(index);
            JsonNode snapshot = step.has("evidence_set") ?
                data.get("evidence_sets").get(text(step, "evidence_set")) : null;
            random.set(step.get("random_choices"));
            JsonNode previousModel = run.model();
            long previousRevision = run.asmDataRevision();
            JsonNode actual;
            try {
                actual = switch (text(step, "operation")) {
                    case "set_context" -> run.setContext(step.get("context"));
                    case "select" -> run.select();
                    case "begin_execution" -> run.beginExecution(step.get("selection"), step.get("execution"));
                    case "report_execution" -> run.reportExecution(step.get("execution"), text(step, "update_id"),
                        snapshot.get("sources"), snapshot.get("selected_runs"));
                    case "report_not_invoked" -> run.reportNotInvoked(step.get("execution"));
                    case "update_evidence" -> run.updateEvidence(text(step, "update_id"),
                        snapshot.get("sources"), snapshot.get("selected_runs"));
                    case "cancel" -> run.cancel();
                    default -> throw new AssertionError("Unknown fixture operation.");
                };
                if (List.of("selected", "no_action").contains(text(actual, "result_type"))) {
                    assertTrue(actual.has("message") && !text(actual, "message").isBlank());
                    ((ObjectNode) actual).remove("message");
                }
            } catch (AsmException error) {
                actual = object("result_type", "error", "asm_data_revision", run.asmDataRevision(), "code", error.code());
                assertEquals(previousRevision, run.asmDataRevision(), "Rejected operation changed revision.");
                assertEquals(previousModel, run.model(), "Rejected operation changed model.");
            }
            assertEquals(expected.get("responses").get(index), EvidenceJson.parse(actual.toString()),
                "Step " + index + ": " + text(step, "operation"));
            random.finished();
        }
        assertEquals(before, data, "Caller-owned evidence changed.");
    }

}
