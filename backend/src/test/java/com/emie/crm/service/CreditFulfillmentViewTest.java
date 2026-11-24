package com.emie.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreditFulfillmentViewTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ObjectNode order() { return mapper.createObjectNode().put("status","审批中"); }
  private ObjectNode shipment() { return mapper.createObjectNode().put("status","ACCEPTED").put("oId",123).put("nativeStatus","WaitConfirm"); }
  private ObjectNode approval(String status) { return mapper.createObjectNode().put("status",status); }

  @Test void pendingApprovalAndAcceptedErpOrderAreTheNormalParallelWorkflow() {
    var order=order();var shipment=shipment();var approval=approval("PENDING");
    assertTrue(CreditFulfillmentView.operational(shipment));
    assertFalse(CreditFulfillmentView.exception(order,shipment,approval));
    assertEquals("",CreditFulfillmentView.message(order,shipment,approval));
    assertEquals(false,CreditFulfillmentView.fields(order,shipment,approval).get("fulfillmentException"));
  }

  @Test void shippingBeforeTheParallelDecisionFinishesNeedsExplicitReview() {
    for(String status:new String[]{"SHIPPED","PARTIAL"}) {
      var shipment=shipment().put("status",status);
      assertTrue(CreditFulfillmentView.exception(order(),shipment,approval("PENDING")));
      String message=CreditFulfillmentView.message(order(),shipment,approval("PENDING"));
      assertTrue(message.contains("并行处理中"));assertTrue(message.contains("待核对"));
    }
    assertTrue(CreditFulfillmentView.exception(order(),shipment().put("nativeStatus","Sent"),approval("PENDING")));
  }

  @Test void rejectedCancelledAndFailedDecisionsStillExposeTheExistingErpOrder() {
    for(String status:new String[]{"REJECTED","CANCELED","FAILED","DELETED","UNKNOWN"})
      assertTrue(CreditFulfillmentView.exception(order(),shipment(),approval(status)),status);
    assertTrue(CreditFulfillmentView.exception(order().put("status","草稿"),shipment(),approval("FAILED")));
    assertTrue(CreditFulfillmentView.exception(order().put("status","已取消"),shipment(),approval("CANCELED")));
  }
}
