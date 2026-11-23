package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class FeishuSalesApprovalFlow {
  private FeishuSalesApprovalFlow() {}

  static ObjectNode empty(ObjectMapper mapper, String status) {
    var result = mapper.createObjectNode().put("status", status).put("previewStatus", "NOT_APPLICABLE");
    result.putArray("nodes");
    result.putArray("timeline");
    return result;
  }

  static ObjectNode build(ObjectMapper mapper, JsonNode instance, Map<String, String> names,
      Function<JsonNode, JsonNode> preview) {
    String status = instance.path("status").asText("UNKNOWN");
    var result = empty(mapper, status);
    var nodes = (ArrayNode) result.path("nodes");
    var actual = new LinkedHashMap<String, ObjectNode>();
    var active = new LinkedHashMap<String, List<JsonNode>>();
    for (var source : chronological(instance.path("task_list"), "start_time")) {
      String taskId = source.path("id").asText();
      String nodeId = source.path("node_id").asText(taskId);
      if (taskId.isBlank() || nodeId.isBlank()) continue;
      String taskStatus = source.path("status").asText("UNKNOWN");
      var node = actual.computeIfAbsent(nodeId,
          id -> addNode(nodes, id, source.path("node_name").asText("审批节点"), taskStatus));
      var task = ((ArrayNode) node.path("tasks")).addObject().put("id", taskId).put("status", taskStatus);
      putName(task, names, source.path("open_id").asText());
      putTime(task, "startedAt", source.path("start_time"));
      putTime(task, "finishedAt", source.path("end_time"));
      if ("PENDING".equals(status) && "PENDING".equals(taskStatus))
        active.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(source);
      if (active.containsKey(nodeId)) node.put("status", "PENDING");
      else if (decision(taskStatus) || !decision(node.path("status").asText())) node.put("status", taskStatus);
    }
    if ("PENDING".equals(status))
      addPreview(result, nodes, actual, active, names, preview);
    addTimeline((ArrayNode) result.path("timeline"), instance, names);
    return result;
  }

  private static void addPreview(ObjectNode result, ArrayNode nodes, Map<String, ObjectNode> actual,
      Map<String, List<JsonNode>> active, Map<String, String> names, Function<JsonNode, JsonNode> preview) {
    int successful = 0;
    var upcoming = new LinkedHashMap<String, ObjectNode>();
    var participants = new LinkedHashMap<String, Set<String>>();
    for (var tasks : active.values()) {
      var candidate = tasks.stream().filter(task -> !task.path("open_id").asText().isBlank()).findFirst();
      if (candidate.isEmpty()) continue;
      JsonNode previewed;
      try {
        previewed = preview.apply(candidate.get());
      } catch (ApiException failure) {
        continue;
      }
      if (previewed == null || !previewed.isArray()) continue;
      successful++;
      for (var source : previewed) {
        String id = source.path("node_id").asText();
        if (id.isBlank() || actual.containsKey(id)) continue;
        var node = upcoming.computeIfAbsent(id,
            key -> addNode(nodes, key, source.path("node_name").asText("审批节点"), "UPCOMING"));
        var seen = participants.computeIfAbsent(id, ignored -> new LinkedHashSet<>());
        var tasksForNode = (ArrayNode) node.path("tasks");
        for (var user : source.path("user_id_list")) {
          if (user.asText().isBlank() || !seen.add(user.asText())) continue;
          var task = tasksForNode.addObject().put("id", id + "-" + tasksForNode.size()).put("status", "UPCOMING");
          putName(task, names, user.asText());
        }
      }
    }
    result.put("previewStatus", successful == 0 ? "UNAVAILABLE" : successful == active.size() ? "AVAILABLE" : "PARTIAL");
  }

  private static boolean decision(String status) {
    return "APPROVED".equals(status) || "REJECTED".equals(status);
  }

  private static void addTimeline(ArrayNode timeline, JsonNode instance, Map<String, String> names) {
    var events = chronological(instance.path("timeline"), "create_time");
    if (events.stream().noneMatch(event -> "START".equals(event.path("type").asText()))
        && instance.path("start_time").asLong(0) > 0) {
      var start = timeline.addObject().put("id", "start").put("type", "START");
      putName(start, names, instance.path("open_id").asText());
      putTime(start, "at", instance.path("start_time"));
    }
    for (var source : events) {
      String type = source.path("type").asText();
      if (type.isBlank()) continue;
      var event = timeline.addObject().put("id", "event-" + timeline.size()).put("type", type);
      putName(event, names, source.path("open_id").asText());
      putTime(event, "at", source.path("create_time"));
      if (source.path("comment").isTextual() && !source.path("comment").asText().isBlank())
        event.set("comment", source.path("comment"));
    }
  }

  private static List<JsonNode> chronological(JsonNode items, String field) {
    var sorted = new ArrayList<JsonNode>();
    if (items.isArray()) items.forEach(sorted::add);
    sorted.sort(Comparator.comparingLong(item -> {
      long time = item.path(field).asLong(0);
      return time > 0 ? time : Long.MAX_VALUE;
    }));
    return sorted;
  }

  private static ObjectNode addNode(ArrayNode nodes, String id, String name, String status) {
    var node = nodes.addObject().put("id", id).put("name", name).put("status", status);
    node.putArray("tasks");
    return node;
  }

  private static void putName(ObjectNode target, Map<String, String> names, String openId) {
    String name = names.get(openId);
    if (name != null && !name.isBlank()) target.put("name", name);
    else if (!openId.isBlank()) target.put("name", "飞书成员（姓名待解析）").put("nameUnavailable", true);
  }

  private static void putTime(ObjectNode target, String field, JsonNode source) {
    long time = source.asLong(0);
    if (time > 0) target.put(field, time);
  }
}
