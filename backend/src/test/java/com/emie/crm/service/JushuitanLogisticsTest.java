package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JushuitanLogisticsTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ObjectNode carrier() {
    return mapper.createObjectNode().put("logisticsMode","CARRIER").put("logisticsCode","00012.SF").put("logisticsCompany","顺丰速运");
  }

  @Test void missingLegacyPlanAndExplicitWarehouseDefaultHaveTheSameMeaningWithoutAddingFields() {
    var legacy=mapper.createObjectNode();var explicit=mapper.createObjectNode().put("logisticsMode","WAREHOUSE")
        .put("logisticsCode","").put("logisticsCompany","");
    assertEquals(JushuitanLogistics.selection(legacy),JushuitanLogistics.selection(explicit));
    assertTrue(JushuitanLogistics.sameDraft(legacy,explicit));
    var frozen=mapper.createObjectNode();JushuitanLogistics.copyPlan(frozen,legacy);assertTrue(frozen.isEmpty());
    var request=mapper.createObjectNode();JushuitanLogistics.apply(request,legacy,explicit);
    assertFalse(request.has("lc_id"));assertFalse(request.has("logistics_company"));
  }

  @Test void carrierCodesAreOpaqueAndTheFrozenNameAndCodeMustMatch() {
    var planned=carrier();var before=planned.deepCopy();var request=mapper.createObjectNode();
    JushuitanLogistics.apply(request,planned,planned.deepCopy());
    assertEquals("00012.SF",request.path("lc_id").asText());assertEquals("顺丰速运",request.path("logistics_company").asText());
    assertEquals(before,planned);
    assertThrows(ApiException.class,()->JushuitanLogistics.apply(request,planned,carrier().put("logisticsCode","SF")));
    assertThrows(ApiException.class,()->JushuitanLogistics.apply(request,planned,carrier().put("logisticsCompany","顺丰到付")));
    assertThrows(ApiException.class,()->JushuitanLogistics.apply(request,planned,mapper.createObjectNode()));
  }

  @Test void incompleteCarrierMayBeSavedAsDraftButCannotBecomeASubmittedSelection() {
    var fields=mapper.createObjectNode().put("logisticsMode","CARRIER");
    assertDoesNotThrow(()->JushuitanLogistics.selection(fields,false));
    assertThrows(ApiException.class,()->JushuitanLogistics.selection(fields));
    for(String value:new String[]{"CARRIER ","carrier","UNKNOWN"})
      assertThrows(ApiException.class,()->JushuitanLogistics.selection(fields.deepCopy().put("logisticsMode",value),false));
    assertThrows(ApiException.class,()->JushuitanLogistics.selection(carrier().put("logisticsCode",123),false));
    assertThrows(ApiException.class,()->JushuitanLogistics.selection(carrier().put("logisticsCode","SF\n"),false));
    assertThrows(ApiException.class,()->JushuitanLogistics.selection(carrier().put("logisticsCode","x".repeat(101)),false));
    assertThrows(ApiException.class,()->JushuitanLogistics.selection(carrier().put("logisticsCompany","名".repeat(192)),false));
    assertThrows(ApiException.class,()->JushuitanLogistics.selection(carrier().put("logisticsMode","WAREHOUSE"),false));
  }

  @Test void defaultCompatibilityNeverRelaxesOtherDraftFieldsOrPermitsCarrierChanges() {
    var original=mapper.createObjectNode().put("recipientDistrict","龙岗区");
    var explicit=original.deepCopy().put("logisticsMode","WAREHOUSE");assertTrue(JushuitanLogistics.sameDraft(original,explicit));
    explicit.put("recipientDistrict","福田区");assertFalse(JushuitanLogistics.sameDraft(original,explicit));
    assertFalse(JushuitanLogistics.sameDraft(original,carrier()));
    assertFalse(JushuitanLogistics.sameDraft(carrier(),carrier().put("logisticsCode","SF.5")));
    assertThrows(ApiException.class,()->JushuitanLogistics.requireUnchangedSupplement(carrier(),mapper.createObjectNode().put("logisticsCode","OTHER")));
    assertDoesNotThrow(()->JushuitanLogistics.requireUnchangedSupplement(carrier(),mapper.createObjectNode().put("logisticsCode","00012.SF")));
  }
}
