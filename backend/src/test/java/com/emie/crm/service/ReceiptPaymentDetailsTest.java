package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReceiptPaymentDetailsTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode input() throws Exception {
    return (ObjectNode) mapper.readTree("""
        {"amount":120,"paymentDetails":[
          {"method":"银行A","amount":"50.00","receiptDate":"2026-10-31"},
          {"method":"预收款核销","amount":30,"receiptDate":"2026-11-02"},
          {"method":"银行B","amount":40,"receiptDate":"2026-11-01"}]}
        """);
  }

  private ObjectNode frozen() throws Exception {
    var batch = input();
    var details = ReceiptPaymentDetails.read(batch); batch.set("paymentDetails", details);
    batch.put("method", ReceiptPaymentDetails.method(details)).put("receiptDate", ReceiptPaymentDetails.receiptDate(details));
    var allocations = batch.putArray("allocations");
    allocations.addObject().put("orderId", "one").put("amount", 60);
    allocations.addObject().put("orderId", "two").put("amount", 60);
    ReceiptPaymentDetails.freezeAllocations(details, allocations);
    return batch;
  }

  @Test void detailsAreCanonicalAndSummariesNeverReplaceTheActualMethodOrDates() throws Exception {
    var input = input(); var before = input.deepCopy();
    ((ObjectNode)input.path("paymentDetails").get(0)).put("lineNo", 999);
    var details = ReceiptPaymentDetails.read(input);
    assertEquals(1,details.get(0).path("lineNo").asInt());
    assertEquals("银行A",details.get(0).path("method").asText());
    assertEquals("2026-10-31",details.get(0).path("receiptDate").asText());
    assertEquals(new BigDecimal("120.00"),ReceiptPaymentDetails.total(details));
    assertEquals("组合收款",ReceiptPaymentDetails.method(details));
    assertEquals("2026-11-02",ReceiptPaymentDetails.receiptDate(details));
    assertEquals(new BigDecimal("30.00"),ReceiptPaymentDetails.creditTotal(input));
    assertEquals(before.path("paymentDetails").get(0).path("amount"),input.path("paymentDetails").get(0).path("amount"));
    assertEquals(999,input.path("paymentDetails").get(0).path("lineNo").asInt());
  }

  @Test void fifoFundingPreservesBothTheOrderTotalsAndEveryPaymentRowsAmountAndDate() throws Exception {
    var batch=frozen();ReceiptPaymentDetails.validateFrozen(batch);
    var one=ReceiptPaymentDetails.parts(batch,batch.path("allocations").get(0));
    var two=ReceiptPaymentDetails.parts(batch,batch.path("allocations").get(1));
    assertEquals(2,one.size());assertEquals(2,two.size());
    assertEquals(new BigDecimal("50.00"),one.get(0).path("amount").decimalValue());
    assertEquals("银行A",one.get(0).path("method").asText());
    assertEquals(new BigDecimal("10.00"),one.get(1).path("amount").decimalValue());
    assertEquals("预收款核销",one.get(1).path("method").asText());
    assertEquals(new BigDecimal("20.00"),two.get(0).path("amount").decimalValue());
    assertEquals("2026-11-02",two.get(0).path("receiptDate").asText());
    assertEquals(new BigDecimal("40.00"),two.get(1).path("amount").decimalValue());
    assertEquals("银行B",two.get(1).path("method").asText());
  }

  @Test void tamperedFrozenSummariesRowsAndMatricesCannotBookAsIfTheyWereTheApprovedContents() throws Exception {
    for(String field:new String[]{"amount","method","receiptDate","lineNo","part","missingPart"}) {
      var batch=frozen();
      switch(field) {
        case "amount" -> batch.put("amount",121);
        case "method" -> batch.put("method","银行A");
        case "receiptDate" -> batch.put("receiptDate","2026-10-31");
        case "lineNo" -> ((ObjectNode)batch.path("paymentDetails").get(0)).put("lineNo",2);
        case "part" -> ((ObjectNode)batch.path("allocations").get(0).path("paymentDetails").get(0)).put("amount",49);
        case "missingPart" -> ((ObjectNode)batch.path("allocations").get(1)).remove("paymentDetails");
      }
      assertThrows(ApiException.class,()->ReceiptPaymentDetails.validateFrozen(batch),field);
    }
  }

  @Test void emptyOrMalformedArraysNeverFallBackToLegacyScalarFields() throws Exception {
    var source=input();
    for(var value:new com.fasterxml.jackson.databind.JsonNode[]{mapper.nullNode(),mapper.createObjectNode(),mapper.createArrayNode(),mapper.getNodeFactory().textNode("bad")}) {
      source.set("paymentDetails",value);
      assertTrue(ReceiptPaymentDetails.hasDetails(source));
      assertThrows(ApiException.class,()->ReceiptPaymentDetails.read(source));
    }
    var tooMany=input();var rows=tooMany.putArray("paymentDetails");
    for(int i=0;i<51;i++)rows.addObject().put("method","银行A").put("amount",1).put("receiptDate","2026-11-25");
    assertThrows(ApiException.class,()->ReceiptPaymentDetails.read(tooMany));
  }

  @Test void invalidPaymentAmountsDatesAndSummaryMethodsAreRejected() throws Exception {
    for(String value:new String[]{"0","-1","0.001","NaN","1000000000000",""}) {
      var source=input();((ObjectNode)source.path("paymentDetails").get(0)).put("amount",value);
      assertThrows(ApiException.class,()->ReceiptPaymentDetails.read(source));
    }
    for(String value:new String[]{"2026-02-29","2026-13-01","2026-1-01",""}) {
      var source=input();((ObjectNode)source.path("paymentDetails").get(0)).put("receiptDate",value);
      assertThrows(ApiException.class,()->ReceiptPaymentDetails.read(source));
    }
    var source=input();((ObjectNode)source.path("paymentDetails").get(0)).put("method","组合收款");
    assertThrows(ApiException.class,()->ReceiptPaymentDetails.read(source));
  }

  @Test void repeatedRealMethodsStayARealSummaryAndLegacyPayloadIsReadWithoutMutation() throws Exception {
    var source=mapper.createObjectNode().put("amount",12).put("method","银行A").put("receiptDate","2026-11-25");
    var before=source.deepCopy();var details=ReceiptPaymentDetails.read(source);
    assertEquals("银行A",ReceiptPaymentDetails.method(details));assertEquals(before,source);
    assertFalse(ReceiptPaymentDetails.hasDetails(source));
    assertEquals(BigDecimal.ZERO,ReceiptPaymentDetails.creditTotal(source));
    source.put("method","预收款核销").remove("receiptDate");
    assertEquals(new BigDecimal("12.00"),ReceiptPaymentDetails.creditTotal(source));
  }

  @Test void allocationTotalsAndDuplicateOrdersMustMatchTheWholePaymentPlan() throws Exception {
    for(int amount:new int[]{119,121}) {
      var allocations=mapper.createArrayNode();allocations.addObject().put("orderId","one").put("amount",amount);
      assertThrows(ApiException.class,()->ReceiptPaymentDetails.freezeAllocations(ReceiptPaymentDetails.read(input()),allocations));
    }
    var allocations=mapper.createArrayNode();
    allocations.addObject().put("orderId","one").put("amount",60);allocations.addObject().put("orderId","one").put("amount",60);
    assertThrows(ApiException.class,()->ReceiptPaymentDetails.freezeAllocations(ReceiptPaymentDetails.read(input()),allocations));
  }
}
