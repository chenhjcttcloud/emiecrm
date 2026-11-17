package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class JushuitanClientReadOnlyTest {
  @Test void localReadOnlyModeBlocksEveryWriteBeforeNetworkEvenIfGatewayLaterEnablesWrites() {
    var mapper=new ObjectMapper();
    var client=new JushuitanClient(mapper,"https://invalid.example.test","fixture-token","production");
    ReflectionTestUtils.setField(client,"readOnly",true);
    assertFalse(client.uploadsEnabled());assertFalse(client.changesEnabled());
    var upload=assertThrows(ApiException.class,()->client.upload(mapper.createArrayNode()));
    var change=assertThrows(ApiException.class,()->client.mutate("id","/open/jushuitan/orderbyoid/cancel",mapper.createObjectNode()));
    assertTrue(upload.getMessage().contains("只读"));assertTrue(change.getMessage().contains("只读"));
  }
}
