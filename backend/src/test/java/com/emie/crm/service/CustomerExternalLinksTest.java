package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerExternalLinksTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private void validate(String json) throws Exception { CustomerExternalLinks.validate(mapper.readTree(json)); }

  @Test void legacyCustomersRemainValid() throws Exception {
    validate("[{\"id\":\"c1\",\"name\":\"旧客户\"}]");
  }
  @Test void externalIdsAreUniqueWithinTheirEnvironmentAndType() throws Exception {
    String a="{\"system\":\"jushuitan\",\"environment\":\"production\",\"type\":\"offline\",\"id\":\"0001\"}";
    validate("[{\"externalLinks\":["+a+"]},{\"externalLinks\":["+a.replace("production","sandbox")+"]}]");
    validate("[{\"externalLinks\":["+a+"]},{\"externalLinks\":["+a.replace("offline","buyer")+"]}]");
    assertThrows(ApiException.class,()->validate("[{\"externalLinks\":["+a+"]},{\"externalLinks\":["+a+"]}]"));
    assertThrows(ApiException.class,()->validate("[{\"externalLinks\":["+a+","+a+"]}]"));
  }
  @Test void malformedIdentifiersCannotBypassImportValidation() {
    for(String link : new String[]{"null","{}","{\"system\":\"other\"}",
        "{\"system\":\"jushuitan\",\"environment\":\"production\",\"type\":\"offline\",\"id\":1}",
        "{\"system\":\"jushuitan\",\"environment\":\"production\",\"type\":\"offline\",\"id\":\" 001\"}"})
      assertThrows(ApiException.class,()->validate("[{\"externalLinks\":["+link+"]}]"));
  }
}
