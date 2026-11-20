package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit offline migration, with no HTTP route, ERP writes, orders, or fabricated receipts. */
@Service
public class LegacyCustomerMigrationService {
  private static final Set<String> FIELDS = Set.of("name", "entity", "owner", "status", "channel", "links",
      "contact", "position", "phone", "wechat");
  private final CrmRepository db;
  private final BusinessService business;
  private final ObjectMapper mapper;

  public LegacyCustomerMigrationService(CrmRepository db, BusinessService business, ObjectMapper mapper) {
    this.db = db; this.business = business; this.mapper = mapper;
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
    require(body.path("revision").isIntegralNumber() && body.path("revision").canConvertToLong(), "请先预览旧客户迁移");
    if (body.path("revision").asLong() != state.path("revision").asLong())
      throw new ApiException(409, "业务数据已更新，请重新预览旧客户迁移");
    Prepared prepared = prepare(body, state);
    require(prepared.response.path("summary").path("invalid").asInt() == 0, "旧客户迁移存在冲突，未写入任何客户");
    JsonNode decisions = body.path("decisions");
    require(decisions.isArray() && decisions.size() == prepared.rows.size(), "请明确每行旧客户迁移操作");
    Map<Integer, JsonNode> selected = new HashMap<>();
    for (JsonNode decision : decisions) {
      require(decision.isObject() && decision.path("rowNumber").isIntegralNumber()
          && decision.path("rowNumber").canConvertToInt(), "旧客户迁移操作行号无效");
      int row = decision.path("rowNumber").asInt();
      require(prepared.rows.containsKey(row) && selected.put(row, decision) == null, "旧客户迁移操作行号重复或不存在");
      require(decision.path("action").isTextual()
          && Set.of("CREATE", "LINK", "SKIP").contains(decision.path("action").asText()), "旧客户迁移操作无效");
    }
    ObjectNode next = state.deepCopy();
    ArrayNode customers = (ArrayNode) next.path("customers");
    Map<String, ObjectNode> byId = new HashMap<>();
    customers.forEach(c -> byId.put(c.path("id").asText(), (ObjectNode) c));
    int created = 0, linked = 0, skipped = 0;
    BigDecimal importedOpening = BigDecimal.ZERO;
    for (var entry : prepared.rows.entrySet()) {
      JsonNode row = entry.getValue(); JsonNode decision = selected.get(entry.getKey());
      String action = decision.path("action").asText();
      if (action.equals("SKIP")) { skipped++; continue; }
      require(action.equals(row.path("status").asText()), "旧客户迁移操作与预览不一致");
      JsonNode input = prepared.inputRows.get(entry.getKey());
      ObjectNode customer;
      if (action.equals("CREATE")) {
        customer = mapper.createObjectNode().put("id", "c" + UUID.randomUUID()).put("balance", 0);
        customer.setAll((ObjectNode) row.path("customer"));
        customers.add(customer); created++;
      } else {
        require(decision.path("customerId").isTextual()
            && decision.path("customerId").asText().equals(row.path("customerId").asText()), "旧客户关联目标与预览不一致");
        customer = byId.get(decision.path("customerId").asText());
        require(customer != null, "旧客户关联目标不存在"); linked++;
      }
      ObjectNode source = body.path("source").deepCopy();
      source.put("system", LegacyCustomerData.SYSTEM).put("id", input.path("legacyId").asText())
          .put("rowNumber", entry.getKey()).put("importedAt", CrmRepository.now());
      source.set("fields", input.path("raw").deepCopy());
      customer.set("legacySource", source);
      if (input.hasNonNull("openingBalance")) {
        BigDecimal amount = LegacyCustomerData.amount(input.path("openingBalance"));
        customer.put("openingBalance", amount); importedOpening = importedOpening.add(amount);
      }
    }
    JsonNode saved = state;
    if (created + linked > 0) {
      ObjectNode save = mapper.createObjectNode().put("revision", state.path("revision").asLong());
      save.set("data", next);
      saved = business.saveLegacyMigration(save, actor);
      ObjectNode detail = mapper.createObjectNode().put("type", "LEGACY_CUSTOMER_MIGRATION")
          .put("source", LegacyCustomerData.SYSTEM).put("sha256", body.path("source").path("sha256").asText())
          .put("created", created).put("linked", linked).put("skipped", skipped).put("openingBalanceTotal", importedOpening);
      db.audit(actor.name(), "迁入旧客户档案及期初预收款余额", detail);
    }
    ObjectNode response = mapper.createObjectNode(); response.set("state", saved);
    response.set("summary", mapper.createObjectNode().put("created", created).put("linked", linked)
        .put("skipped", skipped).put("openingBalanceTotal", importedOpening));
    return response;
  }

  private record Prepared(ObjectNode response, Map<Integer, ObjectNode> rows, Map<Integer, JsonNode> inputRows) {}

  private Prepared prepare(JsonNode body, JsonNode state) {
    require(body.isObject() && body.path("source").isObject(), "旧客户来源无效");
    JsonNode source = body.path("source");
    require(source.path("sha256").isTextual() && source.path("sha256").asText().matches("[a-f0-9]{64}"), "旧客户来源文件校验值无效");
    LegacyCustomerData.text(source, "fileName", 255, true); LegacyCustomerData.text(source, "sheet", 200, true);
    if (source.has("exportedAt")) LegacyCustomerData.text(source, "exportedAt", 100, false);
    for (var property : source.properties())
      require(Set.of("sha256", "fileName", "sheet", "exportedAt").contains(property.getKey()), "旧客户来源字段无效");
    JsonNode rows = body.path("rows");
    require(rows.isArray() && !rows.isEmpty() && rows.size() <= 2000, "每次迁入1至2000位旧客户");
    LegacyCustomerData.validate(state.path("customers"));
    Map<String, List<JsonNode>> names = new HashMap<>(); Map<String, JsonNode> legacyIds = new HashMap<>();
    for (JsonNode customer : state.path("customers")) {
      names.computeIfAbsent(customer.path("name").asText(), k -> new ArrayList<>()).add(customer);
      if (customer.has("legacySource")) legacyIds.put(customer.path("legacySource").path("id").asText(), customer);
    }
    Map<String, Integer> inputIds = new HashMap<>(), inputNames = new HashMap<>();
    Map<Integer, JsonNode> input = new LinkedHashMap<>();
    for (JsonNode row : rows) {
      require(row.isObject() && row.path("rowNumber").isIntegralNumber() && row.path("rowNumber").canConvertToInt()
          && row.path("rowNumber").asInt() >= 2 && row.path("rowNumber").asInt() <= 1_000_000,
          "旧客户来源行号无效");
      require(input.put(row.path("rowNumber").asInt(), row) == null, "旧客户来源行号重复");
      LegacyCustomerData.text(row, "legacyId", 100, true);
      inputIds.merge(row.path("legacyId").asText(), 1, Integer::sum);
      require(row.path("customer").isObject(), "旧客户档案结构无效");
      String name = row.path("customer").path("name").asText().trim();
      require(!name.isEmpty() && name.length() <= 200, "旧客户名称不能为空或超过200字");
      inputNames.merge(name, 1, Integer::sum);
      LegacyCustomerData.raw(row.path("raw"));
      if (row.hasNonNull("openingBalance")) LegacyCustomerData.amount(row.path("openingBalance"));
      LegacyCustomerData.basis(row.path("raw"), row.path("legacyId").asText(), row.path("openingBalance"));
      require(row.path("raw").path("客户名称").isTextual()
          && name.equals(row.path("raw").path("客户名称").asText().trim()), "客户名称与旧客户原始依据不一致");
    }
    List<JsonNode> receipts = db.documents("receipts", false, 0);
    ObjectNode response = mapper.createObjectNode().put("revision", state.path("revision").asLong());
    ArrayNode output = response.putArray("rows"); Map<Integer, ObjectNode> prepared = new LinkedHashMap<>();
    int created = 0, linked = 0, already = 0, invalid = 0; BigDecimal total = BigDecimal.ZERO;
    for (var entry : input.entrySet()) {
      JsonNode original = entry.getValue(); String legacyId = original.path("legacyId").asText();
      ObjectNode customer = mapper.createObjectNode();
      for (var field : original.path("customer").properties())
        require(FIELDS.contains(field.getKey()), "旧客户迁移只接受客户主档字段");
      for (String field : FIELDS) {
        JsonNode value = original.path("customer").path(field);
        require(value.isMissingNode() || value.isTextual(), "旧客户资料字段必须为文本");
        String text = value.asText("").trim();
        require(text.length() <= 5000, "旧客户资料字段过长"); customer.put(field, text);
      }
      String name = customer.path("name").asText();
      ObjectNode row = output.addObject().put("rowNumber", entry.getKey()); row.set("customer", customer);
      ArrayNode errors = row.putArray("errors");
      if (inputIds.get(legacyId) > 1) errors.add("文件内旧客户ID重复");
      if (inputNames.get(name) > 1) errors.add("文件内客户名称重复");
      JsonNode target = legacyIds.get(legacyId);
      String status;
      if (target != null) {
        status = "ALREADY_IMPORTED";
        boolean openingPresent = original.hasNonNull("openingBalance");
        if (target.has("openingBalance") != openingPresent
            || (openingPresent && LegacyCustomerData.opening(target).compareTo(LegacyCustomerData.amount(original.path("openingBalance"))) != 0))
          errors.add("旧客户已迁入，当前文件的期初余额与首次迁入不一致，不能重复增加");
      } else {
        List<JsonNode> matches = names.getOrDefault(name, List.of());
        status = matches.isEmpty() ? "CREATE" : "LINK";
        if (matches.size() > 1) errors.add("系统存在多个同名客户，不能自动关联");
        if (matches.size() == 1) {
          target = matches.getFirst();
          if (target.has("legacySource") || target.has("openingBalance")) errors.add("同名客户已有不同旧系统来源，不能覆盖");
          if (original.hasNonNull("openingBalance") && LegacyCustomerData.amount(original.path("openingBalance")).signum() > 0) {
            String id = target.path("id").asText();
            boolean hasHistory = receipts.stream().anyMatch(r -> id.equals(r.path("customerId").asText())
                || (r.path("customerId").asText().isBlank() && name.equals(r.path("customer").asText())));
            String balance = target.path("balance").asText("0");
            boolean zeroBalance = balance.length() <= 40 && balance.matches("-?[0-9]+(?:\\.[0-9]+)?")
                && new BigDecimal(balance).signum() == 0;
            if (!zeroBalance || hasHistory)
              errors.add("现有客户已有余额或收款历史，请先核对期初余额，避免重复计入");
          }
        }
      }
      if (target != null) row.put("customerId", target.path("id").asText());
      if (!errors.isEmpty()) status = "INVALID";
      row.put("status", status); prepared.put(entry.getKey(), row);
      switch (status) {
        case "CREATE" -> created++;
        case "LINK" -> linked++;
        case "ALREADY_IMPORTED" -> already++;
        default -> invalid++;
      }
      if ((status.equals("CREATE") || status.equals("LINK")) && original.hasNonNull("openingBalance"))
        total = total.add(LegacyCustomerData.amount(original.path("openingBalance")));
    }
    response.set("summary", mapper.createObjectNode().put("created", created).put("linked", linked)
        .put("alreadyImported", already).put("invalid", invalid).put("openingBalanceTotal", total));
    return new Prepared(response, prepared, input);
  }

  private JsonNode current(boolean lock) {
    JsonNode state = db.state(lock);
    require(state != null && state.isObject() && state.path("customers").isArray(), "业务数据尚未初始化");
    return state;
  }

  private void permissions(Actor actor) {
    actor.require("business.read"); actor.require("business.write"); actor.require("payment.write"); actor.require("system.manage");
  }
}
