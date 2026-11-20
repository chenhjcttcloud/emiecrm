package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Imports customer master data only. Existing records are linked without replacing their data. */
@Service
public class CustomerImportService {
  private static final List<String> FIELDS = List.of("name", "entity", "owner", "status", "channel",
      "links", "contact", "position", "phone", "wechat", "externalId");
  private static final List<String> REQUIRED = List.of("name", "entity", "owner", "status", "channel", "contact", "phone");
  private static final Map<String, String> LABELS = Map.of("name", "客户名称", "entity", "客户主体", "owner", "负责人",
      "status", "客户状态", "channel", "销售渠道", "contact", "联系人", "phone", "联系电话");
  private static final Set<String> DEFAULT_FIELDS = Set.of("entity", "owner", "status", "channel");
  private static final Set<String> TYPES = Set.of("offline", "distributor", "buyer");
  private static final int MAX_MATCHES = 20;
  private final CrmRepository db;
  private final BusinessService business;
  private final ObjectMapper mapper;

  public CustomerImportService(CrmRepository db, BusinessService business, ObjectMapper mapper) {
    this.db = db; this.business = business; this.mapper = mapper;
  }

  public JsonNode parse(String filename, byte[] bytes, Actor actor) {
    permissions(actor);
    return CustomerImportFile.parse(mapper, filename, bytes);
  }

  @Transactional(readOnly = true)
  public ObjectNode preview(JsonNode body, Actor actor) {
    permissions(actor);
    return prepare(body, current(false)).response;
  }

  @Transactional
  public ObjectNode commit(JsonNode body, Actor actor) {
    permissions(actor);
    JsonNode state = current(true);
    JsonNode revision = body.path("revision");
    require(revision.isIntegralNumber() && revision.canConvertToLong() && revision.asLong() >= 0,
        "请先预览客户导入，再确认提交");
    if (revision.asLong() != state.path("revision").asLong())
      throw new ApiException(409, "客户或业务数据已更新，请重新预览后导入");
    Prepared prepared = prepare(body, state);
    JsonNode decisions = body.path("decisions");
    require(decisions.isArray() && decisions.size() == prepared.rows.size(), "请为每一行选择导入操作");
    Map<Integer, JsonNode> byRow = new HashMap<>();
    for (JsonNode decision : decisions) {
      require(decision.isObject() && decision.path("rowNumber").isIntegralNumber()
          && decision.path("rowNumber").canConvertToInt(), "导入操作行号无效");
      int number = decision.path("rowNumber").asInt();
      require(prepared.rows.containsKey(number) && byRow.put(number, decision) == null, "导入操作行号重复或不存在");
      require(decision.path("action").isTextual() && Set.of("CREATE", "LINK", "SKIP").contains(decision.path("action").asText()),
          "导入操作无效");
    }
    ObjectNode next = state.deepCopy();
    ArrayNode customers = (ArrayNode) next.path("customers");
    Map<String, ObjectNode> customerIndex = new HashMap<>();
    for (JsonNode customer : customers) customerIndex.put(customer.path("id").asText(), (ObjectNode) customer);
    int created = 0, linked = 0, skipped = 0;
    for (var entry : prepared.rows.entrySet()) {
      ObjectNode row = entry.getValue();
      JsonNode decision = byRow.get(entry.getKey());
      String action = decision.path("action").asText();
      if (action.equals("SKIP")) { skipped++; continue; }
      ObjectNode data = (ObjectNode) row.path("customer");
      if (action.equals("CREATE")) {
        require(row.path("createable").asBoolean(), "第 " + entry.getKey() + " 行不能新建，请检查错误或关联已有客户");
        ObjectNode customer = mapper.createObjectNode().put("id", "c" + UUID.randomUUID()).put("balance", 0);
        for (String field : FIELDS) if (!field.equals("externalId")) customer.set(field, data.path(field));
        appendLink(customer, data, prepared.environment, prepared.type);
        customers.add(customer); customerIndex.put(customer.path("id").asText(), customer); created++;
      } else {
        require(row.path("linkable").asBoolean(), "第 " + entry.getKey() + " 行缺少外部客户编号或存在格式/重复错误，不能关联");
        require(decision.path("customerId").isTextual(), "请选择要关联的 CRM 客户");
        String id = decision.path("customerId").asText();
        ObjectNode customer = customerIndex.get(id);
        require(customer != null, "要关联的 CRM 客户不存在，请重新预览");
        String exact = row.path("customerId").asText();
        require(exact.isEmpty() || exact.equals(id), "此外部客户编号已关联其他 CRM 客户，不能换绑");
        if (appendLink(customer, data, prepared.environment, prepared.type)) linked++;
        else skipped++;
      }
    }
    CustomerExternalLinks.validate(customers);
    JsonNode saved = state;
    if (created > 0 || linked > 0) {
      ObjectNode save = mapper.createObjectNode().put("revision", state.path("revision").asLong());
      save.set("data", next);
      saved = business.save(save, actor);
    }
    ObjectNode result = mapper.createObjectNode(); result.set("state", saved);
    result.set("summary", mapper.createObjectNode().put("created", created).put("linked", linked).put("skipped", skipped));
    return result;
  }

  private record Prepared(ObjectNode response, LinkedHashMap<Integer, ObjectNode> rows, String environment, String type) {}

  private Prepared prepare(JsonNode body, JsonNode state) {
    require(body.isObject(), "客户导入参数无效");
    String environment = option(body, "environment", Set.of("production", "sandbox"));
    String type = option(body, "customerType", TYPES);
    JsonNode inputRows = body.path("rows");
    require(inputRows.isArray() && !inputRows.isEmpty() && inputRows.size() <= 2000, "每次请导入 1 至 2000 行客户");
    JsonNode defaults = body.path("defaults");
    require(defaults.isMissingNode() || defaults.isObject(), "默认客户资料格式无效");
    if (defaults.isObject()) for (var property : defaults.properties())
      require(DEFAULT_FIELDS.contains(property.getKey()) && property.getValue().isTextual(), "默认客户资料字段无效");

    Map<String, JsonNode> external = new HashMap<>();
    Map<String, List<JsonNode>> names = new HashMap<>(), entities = new HashMap<>();
    CustomerExternalLinks.validate(state.path("customers"));
    for (JsonNode customer : state.path("customers")) {
      names.computeIfAbsent(customer.path("name").asText(), key -> new ArrayList<>()).add(customer);
      String entity = customer.path("entity").asText().trim();
      if (!entity.isEmpty()) entities.computeIfAbsent(entity, key -> new ArrayList<>()).add(customer);
      for (JsonNode link : customer.path("externalLinks"))
        if (link.path("system").asText().equals("jushuitan") && link.path("environment").asText().equals(environment)
            && link.path("type").asText().equals(type)) external.put(link.path("id").asText(), customer);
    }
    Map<String, Integer> inputIds = new HashMap<>(), inputNames = new HashMap<>();
    LinkedHashMap<Integer, ObjectNode> normalized = new LinkedHashMap<>();
    Map<Integer, List<String>> structural = new HashMap<>();
    for (JsonNode input : inputRows) {
      require(input.isObject() && input.path("rowNumber").isIntegralNumber()
          && input.path("rowNumber").canConvertToInt() && input.path("rowNumber").asInt() >= 2
          && input.path("rowNumber").asInt() <= 1_000_000, "客户导入行号无效");
      int rowNumber = input.path("rowNumber").asInt();
      require(!normalized.containsKey(rowNumber), "客户导入行号重复");
      for (var property : input.properties()) require(FIELDS.contains(property.getKey()) || property.getKey().equals("rowNumber"),
          "客户导入仅允许客户档案字段，不能导入余额或业务数据");
      List<String> invalid = new ArrayList<>();
      ObjectNode row = mapper.createObjectNode();
      for (String field : FIELDS) {
        JsonNode value = input.path(field);
        if (!value.isMissingNode() && !value.isNull() && !value.isTextual()) invalid.add(label(field) + "必须按文本填写");
        String text = value.isTextual() ? value.asText().trim() : "";
        if (text.isEmpty() && DEFAULT_FIELDS.contains(field)) text = defaults.path(field).asText("").trim();
        int maximum = field.equals("links") ? 2000 : field.equals("externalId") ? 100 : field.equals("phone") ? 50 : 200;
        if (text.length() > maximum || text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
          invalid.add(label(field) + "过长或含无效字符");
        if (!field.equals("links") && (text.contains("\n") || text.contains("\r") || text.contains("\t")))
          invalid.add(label(field) + "不能换行或包含制表符");
        row.put(field, text);
      }
      String phone = row.path("phone").asText();
      if (!phone.isEmpty() && !phone.matches("[0-9+() -]{7,50}")) invalid.add("联系电话格式无效，请按文本填写完整号码");
      String externalId = row.path("externalId").asText();
      if (externalId.matches("(?i)[+-]?[0-9]+(?:\\.[0-9]+)?e[+-]?[0-9]+")) invalid.add("外部客户编号不能使用科学计数法，请按文本填写");
      if (!externalId.isEmpty()) inputIds.merge(externalId, 1, Integer::sum);
      String name = row.path("name").asText();
      if (!name.isEmpty()) inputNames.merge(name, 1, Integer::sum);
      normalized.put(rowNumber, row); structural.put(rowNumber, invalid);
    }
    ObjectNode result = mapper.createObjectNode().put("revision", state.path("revision").asLong());
    ArrayNode output = result.putArray("rows");
    LinkedHashMap<Integer, ObjectNode> prepared = new LinkedHashMap<>();
    Map<String, Integer> counts = new HashMap<>();
    for (var entry : normalized.entrySet()) {
      ObjectNode data = entry.getValue(); String name = data.path("name").asText(), externalId = data.path("externalId").asText();
      List<String> invalid = structural.get(entry.getKey());
      if (!externalId.isEmpty() && inputIds.get(externalId) > 1) invalid.add("文件内外部客户编号重复");
      JsonNode exact = externalId.isEmpty() ? null : external.get(externalId);
      if (!name.isEmpty() && inputNames.get(name) > 1 && !names.containsKey(name) && exact == null)
        invalid.add("文件内新客户名称重复，请先区分客户名称");
      if (name.isEmpty()) invalid.add("请填写客户名称");
      String entity = data.path("entity").asText();
      List<JsonNode> nameMatches = names.getOrDefault(name, List.of());
      List<JsonNode> entityMatches = entities.getOrDefault(entity, List.of());
      int matchesTotal = nameMatches.size();
      // Count the union without materializing it. One common legal entity can own thousands of stores.
      for (JsonNode match : entityMatches) if (!match.path("name").asText().equals(name)) matchesTotal++;
      LinkedHashMap<String, JsonNode> matches = new LinkedHashMap<>();
      for (JsonNode match : nameMatches) {
        if (matches.size() >= MAX_MATCHES) break;
        matches.put(match.path("id").asText(), match);
      }
      for (JsonNode match : entityMatches) {
        if (matches.size() >= MAX_MATCHES) break;
        matches.put(match.path("id").asText(), match);
      }
      if (exact != null) {
        if (!exact.path("name").asText().equals(name)
            && (entity.isEmpty() || !exact.path("entity").asText().trim().equals(entity))) matchesTotal++;
        String id = exact.path("id").asText();
        if (!matches.containsKey(id) && matches.size() >= MAX_MATCHES) {
          String last = "";
          for (String key : matches.keySet()) last = key;
          matches.remove(last);
        }
        matches.put(id, exact);
      }
      List<String> errors = new ArrayList<>(invalid);
      for (String field : REQUIRED) if (data.path(field).asText().isEmpty() && !field.equals("name")) errors.add("新建客户需填写" + label(field));
      String status = !invalid.isEmpty() ? "INVALID" : exact != null ? "MATCHED" : matchesTotal > 0 ? "REVIEW" : errors.isEmpty() ? "NEW" : "INVALID";
      ObjectNode row = output.addObject().put("rowNumber", entry.getKey()).put("status", status)
          .put("createable", errors.isEmpty() && exact == null && !names.containsKey(name))
          .put("linkable", invalid.isEmpty() && !externalId.isEmpty())
          .put("matchesTotal", matchesTotal).put("matchesTruncated", matchesTotal > matches.size());
      row.set("customer", data);
      if (exact != null) row.put("customerId", exact.path("id").asText());
      ArrayNode candidates = row.putArray("matches");
      for (JsonNode match : matches.values()) candidates.addObject().put("id", match.path("id").asText())
          .put("name", match.path("name").asText()).put("entity", match.path("entity").asText());
      ArrayNode errorList = row.putArray("errors"); errors.forEach(errorList::add);
      row.put("message", status.equals("MATCHED") ? "此外部客户已有关联，可跳过重复导入" : status.equals("REVIEW") ? "存在同名或同主体客户，请确认后关联" : status.equals("NEW") ? "可以新建客户" : String.join("；", errors));
      counts.merge(status, 1, Integer::sum); prepared.put(entry.getKey(), row);
    }
    result.set("summary", mapper.createObjectNode().put("new", counts.getOrDefault("NEW", 0))
        .put("matched", counts.getOrDefault("MATCHED", 0)).put("review", counts.getOrDefault("REVIEW", 0))
        .put("invalid", counts.getOrDefault("INVALID", 0)));
    return new Prepared(result, prepared, environment, type);
  }

  private boolean appendLink(ObjectNode customer, JsonNode data, String environment, String type) {
    String id = data.path("externalId").asText();
    if (id.isEmpty()) return false;
    for (JsonNode existing : customer.path("externalLinks"))
      if (existing.path("system").asText().equals("jushuitan") && existing.path("environment").asText().equals(environment)
          && existing.path("type").asText().equals(type) && existing.path("id").asText().equals(id)) return false;
    ArrayNode links = customer.has("externalLinks") ? (ArrayNode) customer.path("externalLinks") : customer.putArray("externalLinks");
    links.addObject().put("system", "jushuitan").put("environment", environment).put("type", type)
        .put("id", id).put("name", data.path("name").asText());
    return true;
  }

  private JsonNode current(boolean lock) {
    JsonNode state = db.state(lock);
    require(state != null && state.isObject() && state.path("customers").isArray(), "请先初始化 CRM 业务数据");
    return state;
  }

  private String option(JsonNode body, String field, Set<String> allowed) {
    require(body.path(field).isTextual() && allowed.contains(body.path(field).asText()), "客户导入" + (field.equals("environment") ? "环境" : "类型") + "无效");
    return body.path(field).asText();
  }

  private String label(String field) { return LABELS.getOrDefault(field, field.equals("externalId") ? "外部客户编号" : "客户资料"); }

  private void permissions(Actor actor) { actor.require("business.read"); actor.require("business.write"); }
}
