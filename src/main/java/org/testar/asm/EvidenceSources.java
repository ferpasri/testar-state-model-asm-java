package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.EvidenceReader;
import org.testar.evidence.ReconstructedEvidence;
import static org.testar.asm.Json.*;

/** Prepare selection input using validated evidence, while preserving its original records. */
public final class EvidenceSources {
    private EvidenceSources() {
    }

    /** Read a stored evidence trace and prepare its states and actions for ASM use. */
    public static JsonNode read(Path directory, String sourceId) throws IOException {
        return fromEvidence(EvidenceReader.read(directory), sourceId);
    }

    /** Extract selection-relevant records from reconstructed evidence, retaining the original record identities. */
    public static JsonNode fromEvidence(ReconstructedEvidence evidence, String sourceId) {
        require(sourceId != null && !sourceId.isBlank(), "invalid_evidence", "A source ID is required.");
        var runs = new ArrayList<JsonNode>();
        for (var record : evidence.testRuns()) {
            var run = (com.fasterxml.jackson.databind.node.ObjectNode) EvidenceJson.tree(record.testRun());
            run.put("status", record.finished() ? "finished" : "running");
            run.set("reason", EvidenceJson.tree(record.reason()));
            runs.add(run);
        }
        var sequences = new ArrayList<JsonNode>();
        for (var record : evidence.testSequences()) {
            var sequence = (com.fasterxml.jackson.databind.node.ObjectNode) EvidenceJson.tree(record.testSequence());
            sequence.put("status", record.finished() ? "finished" : "running");
            sequence.set("reason", EvidenceJson.tree(record.reason()));
            sequences.add(sequence);
        }
        var executions = new ArrayList<JsonNode>();
        for (var record : evidence.actionExecutions()) {
            executions.add(object("execution", EvidenceJson.tree(record.actionExecution()),
                "status", record.status().name().toLowerCase(java.util.Locale.ROOT),
                "executionResult", EvidenceJson.tree(record.executionResult()),
                "resultingObservation", record.resultingObservation()));
        }
        JsonNode data = object("runs", runs, "sequences", sequences, "observations", EvidenceJson.tree(evidence.observations()),
            "actions", EvidenceJson.tree(evidence.actionDescriptions()), "executions", executions);
        return object("id", sourceId, "label", sourceId, "fingerprint", ContentHash.source(data), "evidence", data);
    }
}
