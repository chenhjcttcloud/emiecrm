package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderWarehouseTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test void legacyAndExplicitEmptyDraftsDoNotReadTheWarehouseDirectory() throws Exception {
    var order = FeishuApprovalFixtures.order();
    var directory = mock(JushuitanService.class);
    assertFalse(OrderWarehouse.resolve(order, mapper.createObjectNode(), directory).selected());
    order.putObject("approvalDraft").put("warehouseId", "").put("warehouseName", "");
    assertFalse(OrderWarehouse.resolve(order, mapper.createObjectNode(), directory).selected());
    verifyNoInteractions(directory);
  }

  @ParameterizedTest @ValueSource(strings={"0", "-1", "1.5", "01", "NaN", "9223372036854775808", "1e3"})
  void rejectsMalformedOrNonPositiveWarehouseIds(String id) throws Exception {
    var order = FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("warehouseId", id);
    assertThrows(ApiException.class, () -> OrderWarehouse.validateDraft(order));
  }

  @Test void selectedWarehouseMustBelongToAnOrdinarySalesOrder() throws Exception {
    var order = FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "仓库");
    for (var type : java.util.List.of("预收款订单", "其他")) {
      order.put("type", type);
      assertThrows(ApiException.class, () -> OrderWarehouse.validateDraft(order));
    }
    order.put("type", "销售订单").put("source", "jushuitan-settlement");
    assertThrows(ApiException.class, () -> OrderWarehouse.validateDraft(order));
  }

  @Test void resolvesTheRealNameAndRequiresMatchingOrderAndApprovalIds() throws Exception {
    var order = FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "伪造名称");
    var input = mapper.createObjectNode().put("warehouseId", "5").put("warehouseName", "旧名称");
    var directory = mock(JushuitanService.class);
    when(directory.warehouses()).thenReturn(mapper.readTree("[{\"id\":5,\"name\":\"有效仓库\"}]"));
    assertEquals(new OrderWarehouse.Selection("5", "有效仓库"), OrderWarehouse.resolve(order, input, directory));
    input.put("warehouseId", "6");
    assertThrows(ApiException.class, () -> OrderWarehouse.resolve(order, input, directory));
    verify(directory, times(1)).warehouses();
  }

  @Test void doesNotAcceptADeletedWarehouseOrANameWithoutAnId() throws Exception {
    var order = FeishuApprovalFixtures.order();
    var directory = mock(JushuitanService.class);
    when(directory.warehouses()).thenReturn(mapper.createArrayNode());
    order.putObject("approvalDraft").put("warehouseId", "5");
    assertThrows(ApiException.class, () -> OrderWarehouse.resolve(order,
        mapper.createObjectNode().put("warehouseId", "5"), directory));
    order.putObject("approvalDraft").put("warehouseName", "只有名称");
    assertThrows(ApiException.class, () -> OrderWarehouse.validateDraft(order));
  }

  @Test void namesMustBeShortTextWithoutControlCharacters() throws Exception {
    var order = FeishuApprovalFixtures.order();
    var fields = order.putObject("approvalDraft").put("warehouseId", "5");
    fields.put("warehouseName", 5);
    assertThrows(ApiException.class, () -> OrderWarehouse.validateDraft(order));
    fields.put("warehouseName", "仓".repeat(192));
    assertThrows(ApiException.class, () -> OrderWarehouse.validateDraft(order));
    fields.put("warehouseName", "仓\n库");
    assertThrows(ApiException.class, () -> OrderWarehouse.validateDraft(order));
    fields.put("warehouseName", "仓".repeat(191));
    assertDoesNotThrow(() -> OrderWarehouse.validateDraft(order));
  }
}
