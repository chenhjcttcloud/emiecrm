package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class PrepaymentPaymentDetailsTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode proposal() {
    var order = mapper.createObjectNode().put("type", "预收款订单").put("total", 300).put("method", "组合收款").put("paid", 0);
    var rows = order.putArray("paymentDetails");
    rows.addObject().put("lineNo", 999).put("method", "银行转账").put("amount", "100.00").put("receiptDate", "2026-11-17");
    rows.addObject().put("method", "POS").put("amount", 200).put("receiptDate", "2026-11-18");
    return order;
  }

  @Test void canonicalProposalKeepsEveryDateAndMethodAndDoesNotRepresentActualMoney() {
    var order = proposal();
    PrepaymentPaymentDetails.normalize(order);
    assertEquals(300, order.path("total").asInt());
    assertEquals("组合收款", order.path("method").asText());
    assertEquals("2026-11-18", order.path("approvalDraft").path("receiptDate").asText());
    assertEquals(1, order.path("paymentDetails").get(0).path("lineNo").asInt());
    assertEquals(2, order.path("paymentDetails").get(1).path("lineNo").asInt());
    assertEquals("银行转账", order.path("paymentDetails").get(0).path("method").asText());
    assertEquals("2026-11-17", order.path("paymentDetails").get(0).path("receiptDate").asText());
    assertEquals(0, order.path("paid").asInt());
    var before = order.deepCopy();
    var input = mapper.createObjectNode().put("receiptDate", "2026-11-18");
    input.set("paymentDetails", order.path("paymentDetails").deepCopy());
    assertEquals(input, PrepaymentPaymentDetails.approval(order, input));
    assertEquals(before, order);
    var text = PrepaymentPaymentDetails.description(order);
    assertTrue(text.contains("第1笔 | 收款方式：银行转账 | 预收款金额：100.00 元 | 发生日期：2026-11-17"));
    assertTrue(text.contains("第2笔 | 收款方式：POS | 预收款金额：200.00 元 | 发生日期：2026-11-18"));
  }

  @Test void aSingleConfiguredChannelStillKeepsSeparateRowsAndSummaryUsesTheLatestDate() {
    var order = proposal().put("method", "银行转账");
    ((ObjectNode) order.path("paymentDetails").get(1)).put("method", "银行转账").put("receiptDate", "2026-11-16");
    PrepaymentPaymentDetails.normalize(order);
    assertEquals(2, order.path("paymentDetails").size());
    assertEquals("银行转账", order.path("method").asText());
    assertEquals("2026-11-17", order.path("approvalDraft").path("receiptDate").asText());
  }

  @Test void invalidRowsAndSummaryCannotBeSilentlyReinterpreted() {
    for (String field : new String[]{"amount", "receiptDate", "method"}) {
      var order = proposal();
      ((ObjectNode) order.path("paymentDetails").get(0)).put(field,
          field.equals("amount") ? "0.001" : field.equals("receiptDate") ? "2026-02-30" : "预收款核销");
      assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(order), field);
    }
    for (String method : new String[]{"预收款核销", "组合收款", "未知\n方式"}) {
      var order = proposal(); ((ObjectNode) order.path("paymentDetails").get(0)).put("method", method);
      assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(order));
    }
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(proposal().put("total", 301)));
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(proposal().put("method", "伪造汇总")));
    var wrongDate = proposal(); wrongDate.putObject("approvalDraft").put("receiptDate", "2026-11-17");
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(wrongDate));
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(proposal().put("type", "销售订单")));
    var empty = proposal(); empty.putArray("paymentDetails");
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(empty));
    var missing = proposal(); missing.putNull("paymentDetails");
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(missing));
    var tooMany = proposal(); var rows = tooMany.putArray("paymentDetails");
    for (int i = 0; i < 51; i++) rows.addObject().put("method", "银行转账").put("amount", 1).put("receiptDate", "2026-11-18");
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.normalize(tooMany));
  }

  @Test void approvalRowsMustExactlyMatchTheFrozenProposalAndActualReceiptMethodRemainsIndependent() {
    var order = proposal(); PrepaymentPaymentDetails.normalize(order);
    var input = mapper.createObjectNode().put("receiptDate", "2026-11-18");
    input.set("paymentDetails", order.path("paymentDetails").deepCopy());
    var altered = input.deepCopy(); ((ObjectNode) altered.path("paymentDetails").get(0)).put("receiptDate", "2026-11-16");
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.approval(order, altered));
    var fractional = order.deepCopy(); ((ObjectNode) fractional.path("paymentDetails").get(0)).put("lineNo", 1.0);
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.validateRows(fractional));
    var originalRows = order.path("paymentDetails").deepCopy();
    order.put("method", "实际到账账户").put("paid", 10);
    assertDoesNotThrow(() -> PrepaymentPaymentDetails.validateRows(order));
    assertEquals(originalRows, order.path("paymentDetails"));
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.approval(order, input));
  }

  @Test void legacyScalarApprovalDataIsNeverExpandedIntoRows() {
    var order = mapper.createObjectNode().put("type", "预收款订单").put("total", 300).put("method", "旧账户");
    var input = mapper.createObjectNode().put("receiptDate", "2026-11-17").put("receiptCategory", "原类别");
    var beforeOrder = order.deepCopy(); var beforeInput = input.deepCopy();
    assertEquals(input, PrepaymentPaymentDetails.approval(order, input));
    assertEquals(beforeOrder, order); assertEquals(beforeInput, input);
    assertFalse(order.has("paymentDetails"));
    var extra = input.deepCopy(); extra.putArray("paymentDetails");
    assertThrows(ApiException.class, () -> PrepaymentPaymentDetails.approval(order, extra));
  }
}
