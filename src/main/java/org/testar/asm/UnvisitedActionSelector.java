package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import static org.testar.asm.Json.*;

/**
 * Try an unvisited action in the current state. Otherwise, use recorded transitions
 * to navigate toward the nearest state with known unvisited actions.
 */
final class UnvisitedActionSelector extends ActionSelector {
    UnvisitedActionSelector(StateModelData data, JsonNode context, IntUnaryOperator random) {
        super(data, context, random);
    }

    @Override
    ObjectNode select(JsonNode asm) {
        List<JsonNode> rankings = rankings(false, text(asm, "unmatched_choices").equals("isolated"));
        List<JsonNode> eligible = rankings.stream().filter(item -> item.path("eligible").asBoolean()).toList();
        if (eligible.isEmpty()) {
            return object("reason", "no_comparable_choices", "rankings", rankings);
        }
        // A matching execution means this action was already tried, even if it failed.
        List<JsonNode> local = eligible.stream().filter(item -> item.path("recorded_executions").asLong() == 0).toList();
        if (!local.isEmpty()) {
            return object("choice_id", choose(local).get("choice_id"), "reason", "local_unvisited_action", "rankings", rankings);
        }
        return navigate(rankings);
    }

    /** Track one possible navigation path and the states already on it, avoiding cycles within that path. */
    private record Route(String node, List<JsonNode> path, Set<String> visited) {
    }

    /** Order route alternatives consistently before randomly choosing between equally suitable paths. */
    private List<String> routeKey(List<JsonNode> path) {
        var result = new ArrayList<String>();
        for (JsonNode edge : path) {
            result.addAll(key(edge.get("representative"), "execution"));
        }
        return result;
    }

    /** Find the nearest reachable state with unvisited actions and return an available first step toward it. */
    private ObjectNode navigate(List<JsonNode> rankings) {
        Map<String, Integer> unvisited = new HashMap<>();
        for (JsonNode action : actions) {
            String node = text(action, "sourceNode");
            if (representatives.containsKey(node) && !action.get("availability").isEmpty() &&
                    action.get("executions").isEmpty() && !action.path("ambiguous").asBoolean()) {
                unvisited.merge(node, 1, Integer::sum);
            }
        }
        if (unvisited.isEmpty()) {
            return object("reason", "no_unvisited_actions", "rankings", rankings);
        }
        String current = nodes.get(key(context.get("observation"), "observation"));
        Set<String> allowed = new HashSet<>(choiceActions.values());
        var edges = new ArrayList<JsonNode>();
        // The route's first action must still be available in the current GUI state.
        for (JsonNode edge : stateModelData.model.get("edges")) {
            if (text(edge, "kind").equals("execution") && (!text(edge, "source").equals(current) ||
                    allowed.contains(text(edge, "abstractAction")))) {
                ObjectNode copy = edge.deepCopy();
                copy.set("representative", list(edge.get("executions")).stream()
                    .min(Comparator.comparing(item -> key(item, "execution"), KEYS)).orElseThrow());
                edges.add(copy);
            }
        }
        var queue = new ArrayDeque<Route>();
        var visited = new HashSet<String>();
        visited.add(current);
        queue.add(new Route(current, List.of(), visited));
        var routes = new ArrayList<Route>();
        int examined = 0;
        int shortest = Integer.MAX_VALUE;
        // Examine one transition at a time, so nearer states are considered first.
        while (!queue.isEmpty()) {
            Route route = queue.removeFirst();
            require(++examined <= 10000, "processing_limit", "Shortest route search exceeded 10000 alternatives.");
            if (route.path().size() > shortest) {
                break;
            }
            if (!java.util.Objects.equals(route.node(), current) && unvisited.containsKey(route.node())) {
                shortest = route.path().size();
                routes.add(route);
                continue;
            }
            for (JsonNode edge : edges) {
                String target = text(edge, "target");
                if (text(edge, "source").equals(route.node()) && !route.visited().contains(target)) {
                    var path = new ArrayList<>(route.path());
                    path.add(edge);
                    var nextVisited = new HashSet<>(route.visited());
                    nextVisited.add(target);
                    queue.addLast(new Route(target, path, nextVisited));
                }
            }
        }
        if (routes.isEmpty()) {
            return object("reason", "no_reachable_unvisited_actions", "rankings", rankings);
        }
        // Distance is already tied; prefer the state with more distinct unvisited actions.
        int most = routes.stream().mapToInt(route -> unvisited.get(route.node())).max().orElseThrow();
        List<String> targets = routes.stream().map(Route::node).distinct().filter(node -> unvisited.get(node) == most)
            .sorted(Comparator.comparing(node -> key(representatives.get(node), "observation"), KEYS)).toList();
        String target = choose(targets);
        List<Route> paths = routes.stream().filter(route -> route.node().equals(target))
            .sorted(Comparator.comparing(route -> routeKey(route.path()), KEYS)).toList();
        Route route = choose(paths);
        String firstAction = text(route.path().get(0), "abstractAction");
        List<String> choices = choiceActions.entrySet().stream().filter(entry -> entry.getValue().equals(firstAction))
            .map(Map.Entry::getKey).sorted(TEXT).toList();
        return object("choice_id", choose(choices), "reason", "navigate_to_unvisited_actions", "rankings", rankings,
            "navigation", object("target_state", representatives.get(target), "distance", route.path().size(),
                "unvisited_actions", most, "supporting_executions",
                route.path().stream().map(edge -> edge.get("representative")).toList()));
    }
}
