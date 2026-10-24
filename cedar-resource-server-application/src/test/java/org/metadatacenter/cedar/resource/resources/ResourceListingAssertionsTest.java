package org.metadatacenter.cedar.resource.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceListingAssertionsTest {
  @Test void onlyASuccessfulListingCanProveAbsenceInAReadableFolder() throws Exception {
    assertNull(ResourceListingAssertions.find(200, "{\"resources\":[]}", "wanted", false));
    assertEquals("wanted", ResourceListingAssertions.find(200,
        "{\"resources\":[{\"@id\":\"other\"},{\"@id\":\"wanted\"}]}", "wanted", false).path("@id").asText());
    for (int status : new int[]{204, 301, 401, 403, 404, 500, 503}) {
      assertThrows(AssertionError.class, () -> ResourceListingAssertions.find(status, "{}", "wanted", false));
    }
  }

  @Test void hiddenResourcesPermitOnlyExplicitAccessDenials() throws Exception {
    for (int status : new int[]{403, 404}) {
      assertNull(ResourceListingAssertions.find(status, "{}", "wanted", true));
    }
    for (int status : new int[]{204, 301, 401, 500, 503}) {
      assertThrows(AssertionError.class, () -> ResourceListingAssertions.find(status, "{}", "wanted", true));
    }
    assertNotNull(ResourceListingAssertions.find(200, "{\"resources\":[{\"@id\":\"wanted\"}]}", "wanted", true));
  }

  @Test void malformedListingsCannotMasqueradeAsAnEmptyResult() {
    for (String body : new String[]{"null", "{}", "{\"resources\":null}", "{\"resources\":{}}",
        "{\"resources\":[{}]}", "{\"resources\":[{\"@id\":\"\"}]}"}) {
      assertThrows(AssertionError.class, () -> ResourceListingAssertions.find(200, body, "wanted", false));
    }
  }
}
