package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class JushuitanCreditPushPolicyTest {
  final ObjectMapper mapper = new ObjectMapper();
  JsonNode json(String text) throws Exception { return mapper.readTree(text); }
  ObjectNode order() throws Exception {
    return (ObjectNode) json("""
      {"id":"o1","type":"销售订单","status":"已批准","customerId":"c1","customer":"客户甲","owner":"销售","total":10,"paid":0,
       "items":[{"product":"p1","qty":1,"price":10}],"approvalDraft":{"creditFulfillment":true,
       "shopId":"15","shopName":"国内店铺","warehouseId":"42","warehouseName":"主仓",
       "recipientName":"测试收货人","recipientPhone":"00000000000","recipientAddress":"广东省深圳市南山区仅隔离验证不实际发货"}}
      """);
  }
  JsonNode products() throws Exception {
    return json("""
      [{"id":"p1","jstSku":"SKU-1","name":"测试商品","source":"jushuitan","enabled":true,"jstEnvironment":"sandbox","unusedPrivateBlob":"not-required"},
       {"id":"other","jstSku":"OTHER","name":"未引用商品"}]
      """);
  }
  ObjectNode approval(ObjectNode order) throws Exception {
    var approval = mapper.createObjectNode().put("orderId","o1").put("status","APPROVED").put("uuid","uuid")
        .put("instanceCode","instance");
    approval.set("orderSnapshot",order.deepCopy());
    JushuitanCreditPushPolicy.markSubmission(approval,order,products(),"sandbox");
    // This shared fixture represents the retained approval-triggered v1 contract.
    if (approval.has("creditPushPolicyVersion")) approval.put("creditPushPolicyVersion",JushuitanCreditPushPolicy.VERSION);
    return approval;
  }


  @Test void buyerNameAndWarehouseNoteAreValidatedOnlyBeforeCreatingANewAutomaticPushTask() throws Exception {
    for(String invalid : new String[]{"", " ", "长".repeat(51), "客户\n甲"}) {
      var invalidOrder=order().put("customer",invalid);
      var approval=approval(invalidOrder);
      assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.intentForSubmission(approval,invalidOrder,products(),"production"));
      assertFalse(approval.has("creditPush"));
    }
    var invalidOrder=order().put("note","长".repeat(1001));
    var approval=approval(invalidOrder);
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.intentForSubmission(approval,invalidOrder,products(),"production"));
    invalidOrder.put("note","长".repeat(1000));
    assertDoesNotThrow(()->JushuitanCreditPushPolicy.intentForSubmission(approval,invalidOrder,products(),"production"));
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.intentForSubmission(approval,invalidOrder,products(),"sandbox"));
    invalidOrder.put("note","请按客户要求分箱包装");
    assertDoesNotThrow(()->JushuitanCreditPushPolicy.validateDraft(invalidOrder));
  }

  @Test void onlyExplicitNewOrdinarySalesSubmissionsReceiveThePolicy() throws Exception {
    for (String kind : new String[]{"legacy","prepayment","settlement","pickup"}) {
      var order = order();
      if (kind.equals("legacy")) ((ObjectNode) order.path("approvalDraft")).remove("creditFulfillment");
      if (kind.equals("prepayment")) order.put("type","预收款订单");
      if (kind.equals("settlement")) order.put("source","jushuitan-settlement");
      if (kind.equals("pickup")) order.put("type","海外提货单");
      var approval = approval(order);
      JushuitanCreditPushPolicy.enqueue(approval,order);
      assertFalse(approval.has("creditPushPolicyVersion"),kind);
      assertFalse(approval.has("creditPush"),kind);
    }
  }

  @Test void approvalAtomicallyCreatesOneStableIntentWithoutChangingMoneyOrCopyingTheCatalog() throws Exception {
    var order = order(); var approval = approval(order);
    assertEquals(1,approval.path("creditPushProducts").size());
    assertFalse(approval.path("creditPushProducts").get(0).has("unusedPrivateBlob"));
    JushuitanCreditPushPolicy.enqueue(approval,order);
    var push = approval.path("creditPush").deepCopy();
    assertEquals("QUEUED",push.path("status").asText());
    assertTrue(push.path("soId").asText().matches("CRM-[a-f0-9]{32}"));
    assertEquals(0,push.path("orderSnapshot").path("paid").asInt());
    assertEquals(15,push.path("input").path("shopId").asLong());
    assertEquals("SKU-1",push.path("input").path("items").get(0).path("skuId").asText());
    assertFalse(push.path("input").has("pay"));
    assertEquals(0,order.path("paid").asInt());
    JushuitanCreditPushPolicy.enqueue(approval,order);
    assertEquals(push,approval.path("creditPush"));
  }

  @Test void newFormalSubmissionCreatesAnIndependentPendingApprovalIntentWithoutInventingApproval() throws Exception {
    var order=order().put("status","审批中");var approval=approval(order).put("status","QUEUED").put("instanceCode","");
    JushuitanCreditPushPolicy.markSubmission(approval,order,products(),"sandbox");
    assertEquals(2,approval.path("creditPushPolicyVersion").asInt());
    var task=JushuitanCreditPushPolicy.intentForSubmission(approval,order,products(),"sandbox");
    assertTrue(JushuitanCreditPushPolicy.isParallelJob(task));assertEquals("QUEUED",task.path("status").asText());
    assertEquals("ORDER_SUBMISSION",task.path("trigger").asText());assertFalse(task.path("submissionId").asText().isBlank());
    assertEquals("审批中",task.path("orderSnapshot").path("status").asText());assertEquals(0,task.path("orderSnapshot").path("paid").asInt());
    assertEquals("",task.path("instanceCode").asText());assertFalse(task.path("input").has("pay"));
    JushuitanCreditPushPolicy.enqueue(approval,order);assertFalse(approval.has("creditPush"));
  }

  @Test void newShortBusinessNumberIsTheErpIdentityForBothSubmissionAndApprovalTasks() throws Exception {
    String number = "N2611260001";
    var order = order().put("id", number);
    var approval = approval(order).put("orderId", number);
    var submitted = JushuitanCreditPushPolicy.intentForSubmission(approval, order, products(), "sandbox");
    assertEquals(number, submitted.path("soId").asText());
    assertEquals(number, submitted.path("orderSnapshot").path("id").asText());
    JushuitanCreditPushPolicy.enqueue(approval, order);
    assertEquals(number, approval.path("creditPush").path("soId").asText());
    var original = approval.path("creditPush").deepCopy();
    ((ObjectNode) approval.path("creditPush")).put("soId", "CRM-" + "a".repeat(32));
    var retained = approval.path("creditPush").deepCopy();
    JushuitanCreditPushPolicy.enqueue(approval, order);
    assertEquals(retained, approval.path("creditPush"));
    assertEquals(original.path("orderSnapshot"), approval.path("creditPush").path("orderSnapshot"));
  }

  @Test void missingShippingInformationBlocksTheIntentInsteadOfThrowingAwayApproval() throws Exception {
    var order=order(); ((ObjectNode)order.path("approvalDraft")).remove("shopId");
    var approval=approval(order);
    assertDoesNotThrow(()->JushuitanCreditPushPolicy.enqueue(approval,order));
    assertEquals("APPROVED",approval.path("status").asText());
    assertEquals("BLOCKED",approval.path("creditPush").path("status").asText());
    assertTrue(approval.path("creditPush").path("message").asText().contains("店铺"));
  }

  @Test void mismatchedApprovedContentsCannotBecomeAnAutomaticPush() throws Exception {
    var order=order(); var approval=approval(order);
    order.put("total",11);
    JushuitanCreditPushPolicy.enqueue(approval,order);
    assertEquals("BLOCKED",approval.path("creditPush").path("status").asText());
    assertTrue(approval.path("creditPush").path("message").asText().contains("快照"));
  }

  @Test void manualSupplementsFillOnlyMissingApprovedDataAndPreserveTheOriginalIdentity() throws Exception {
    var order=order(); ((ObjectNode)order.path("approvalDraft")).remove("shopId");
    var approval=approval(order); JushuitanCreditPushPolicy.enqueue(approval,order);
    var push=(ObjectNode)approval.path("creditPush"); String so=push.path("soId").asText();
    JushuitanCreditPushPolicy.supplement(push,json("{\"shopId\":15,\"shopName\":\"店铺\"}"));
    assertEquals("QUEUED",push.path("status").asText()); assertEquals(so,push.path("soId").asText());
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.supplement(push,json("{\"warehouseId\":43}")));
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.supplement(push,json("{\"recipient\":{\"address\":\"其他地址\"}}")));
  }

  @Test void summaryNeverExposesRecipientProductSnapshotsOrLeaseOwnership() throws Exception {
    var order=order(); var approval=approval(order); JushuitanCreditPushPolicy.enqueue(approval,order);
    var push=(ObjectNode)approval.path("creditPush"); push.put("attemptId","private-attempt").put("leaseUntil",123);
    var summary=JushuitanCreditPushPolicy.summary(push);
    for(String key:new String[]{"input","orderSnapshot","products","attemptId","approvalUuid","leaseUntil"})assertFalse(summary.has(key),key);
    assertFalse(summary.toString().contains("测试收货人"));
    assertEquals("o1",summary.path("orderId").asText());
  }

  @Test void draftValidationRejectsForgedTypesAndPreservesExactPositiveIds() throws Exception {
    for(String raw:new String[]{"0","-1","1.5","true","null","9223372036854775808"}) {
      var order=order(); ((ObjectNode)order.path("approvalDraft")).set("shopId",json(raw));
      assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.validateDraft(order),raw);
    }
    var order=order(); ((ObjectNode)order.path("approvalDraft")).put("shopId","9223372036854775807");
    assertDoesNotThrow(()->JushuitanCreditPushPolicy.validateDraft(order));
    assertEquals(Long.MAX_VALUE,JushuitanCreditPushPolicy.input(order,products()).path("shopId").longValue());
    ((ObjectNode)order.path("approvalDraft")).put("creditFulfillment","true");
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.validateDraft(order));
  }

  @Test void policyFullAddressInputReachesCreditMapperWithoutEmptyRegionFields() throws Exception {
    var order=order(); var approval=approval(order); JushuitanCreditPushPolicy.enqueue(approval,order);
    var input=approval.path("creditPush").path("input");
    for(String field:new String[]{"province","city","district"})assertFalse(input.path("recipient").has(field),field);
    var mapped=JushuitanOrderMapper.buildCredit(mapper,order,products(),input,approval.path("creditPush").path("soId").asText(),true);
    assertEquals("广东省深圳市南山区仅隔离验证不实际发货",mapped.path("receiver_address").asText());
    assertEquals(0,order.path("paid").asInt());
    assertEquals("账期挂账（非实收）",mapped.path("pay").path("payment").asText());
    assertEquals(10,mapped.path("pay").path("amount").asInt());
  }

  @Test void fullAddressOmitsExistingDirectoryCountyGroupingWithoutChangingTheFrozenDraft() {
    for (String grouping : new String[]{"省直辖县", "省直辖县级行政区划", "省直辖行政单位"}) {
      var draft = mapper.createObjectNode().put("recipientProvince", "湖北省").put("recipientCity", grouping)
          .put("recipientDistrict", "仙桃市").put("recipientAddress", "测试路10号");
      var before = draft.deepCopy();
      assertEquals("湖北省仙桃市测试路10号", JushuitanCreditPushPolicy.fullAddress(draft));
      assertEquals(before, draft);
    }
    var legacy = mapper.createObjectNode().put("recipientAddress", "湖北省省直辖县仙桃市测试路10号");
    assertEquals(legacy.path("recipientAddress").asText(), JushuitanCreditPushPolicy.fullAddress(legacy));
    assertEquals(1, legacy.size());
    var ordinary = mapper.createObjectNode().put("recipientProvince", "广东省").put("recipientCity", "东莞市")
        .put("recipientDistrict", "松山湖").put("recipientAddress", "测试路10号");
    assertEquals("广东省东莞市松山湖测试路10号", JushuitanCreditPushPolicy.fullAddress(ordinary));
  }

  @Test void logisticsPlansAreFrozenAndIncompleteCarrierDraftsCannotBeSubmitted() throws Exception {
    var order=order();var draft=(ObjectNode)order.path("approvalDraft");draft.put("logisticsMode","CARRIER");
    assertDoesNotThrow(()->JushuitanCreditPushPolicy.validateDraft(order));
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.validateSubmission(order));
    draft.put("logisticsCode","00012.SF").put("logisticsCompany","顺丰速运");
    var approval=approval(order);JushuitanCreditPushPolicy.enqueue(approval,order);
    var push=(ObjectNode)approval.path("creditPush");
    assertEquals("00012.SF",push.path("input").path("logisticsCode").asText());
    assertEquals("顺丰速运",push.path("input").path("logisticsCompany").asText());
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.supplement(push,json("{\"logisticsCode\":\"OTHER\"}")));
    var modified=order.deepCopy();((ObjectNode)modified.path("approvalDraft")).put("logisticsCompany","顺丰到付");
    assertFalse(JushuitanCreditPushPolicy.samePlan(order,modified));assertFalse(JushuitanCreditPushPolicy.sameApprovedContents(order,modified));
  }

  @Test void explicitEmptyWarehouseDefaultIsCompatibleWithLegacyButAllOtherDraftFieldsStayStrict() throws Exception {
    var original=order();var defaults=original.deepCopy();
    ((ObjectNode)defaults.path("approvalDraft")).put("logisticsMode","WAREHOUSE").put("logisticsCode","").put("logisticsCompany","");
    assertTrue(JushuitanCreditPushPolicy.samePlan(original,defaults));assertTrue(JushuitanCreditPushPolicy.sameApprovedContents(original,defaults));
    var input=JushuitanCreditPushPolicy.input(original,products());
    for(String field:java.util.List.of("logisticsMode","logisticsCode","logisticsCompany"))assertFalse(input.has(field));
    ((ObjectNode)defaults.path("approvalDraft")).put("recipientDistrict","福田区");
    assertFalse(JushuitanCreditPushPolicy.sameApprovedContents(original,defaults));
    var nonCredit=order();((ObjectNode)nonCredit.path("approvalDraft")).put("creditFulfillment",false).put("logisticsMode","CARRIER");
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.validateSubmission(nonCredit));
  }

  @Test void incompleteHistoricalAddressBlocksIntentAndNewCreditSubmission() throws Exception {
    var valid=order(); var approval=approval(valid);
    var incomplete=order(); ((ObjectNode)incomplete.path("approvalDraft")).put("recipientAddress","万科星火");
    assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.validateSubmission(incomplete));
    var intent=JushuitanCreditPushPolicy.intent(approval,incomplete,products(),"sandbox");
    assertEquals("BLOCKED",intent.path("status").asText()); assertTrue(intent.path("message").asText().contains("完整收货地址"));
    var recipient=(ObjectNode)intent.path("input").path("recipient");
    recipient.put("province","广东省").put("city","深圳市").put("district","龙岗区");
    JushuitanCreditPushPolicy.validateIntent(intent);
    assertEquals("QUEUED",intent.path("status").asText());
    assertEquals("万科星火",intent.path("input").path("recipient").path("address").asText());
    assertEquals("万科星火",incomplete.path("approvalDraft").path("recipientAddress").asText());
  }
}
