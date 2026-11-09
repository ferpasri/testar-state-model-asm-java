package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final Comparator<String> TEXT = (left, right) ->
        Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());
    static final Comparator<List<String>> KEYS = (left, right) -> {
        for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
            int comparison = TEXT.compare(left.get(index), right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    };

    private Json() {
    }

    static ObjectNode object(Object... fields) {
        var values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.length; index += 2) {
            values.put((String) fields[index], fields[index + 1]);
        }
        return MAPPER.valueToTree(values);
    }

    static ArrayNode array(Object value) {
        return MAPPER.valueToTree(value);
    }

    static List<JsonNode> list(JsonNode node) {
        require(node != null && node.isArray(), "invalid_evidence", "A record collection must be an array.");
        var result = new ArrayList<JsonNode>();
        node.forEach(result::add);
        return result;
    }

    static String text(JsonNode value, String field) {
        return value.path(field).asText();
    }

    static List<String> key(JsonNode value, String field) {
        return List.of(text(value, "source"), text(value, "run"), text(value, field));
    }

    static boolean same(JsonNode left, JsonNode right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject() && left.size() == right.size()) {
            var fields = left.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!same(field.getValue(), right.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray() && right.isArray() && left.size() == right.size()) {
            for (int index = 0; index < left.size(); index++) {
                if (!same(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new AsmException(code, message);
        }
    }
}
