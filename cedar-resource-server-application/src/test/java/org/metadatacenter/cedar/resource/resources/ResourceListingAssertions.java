package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.metadatacenter.util.json.JsonMapper;

import java.io.IOException;

/** A failed or malformed listing is not evidence that a resource is absent. */
final class ResourceListingAssertions {
  private ResourceListingAssertions() {}

  static JsonNode find(int status, String body, String id, boolean allowDenied) throws IOException {
    if (allowDenied && (status == 403 || status == 404)) return null;
    Assertions.assertEquals(200, status, body);
    return find(JsonMapper.STRICT_MAPPER.readTree(body), id);
  }

  static JsonNode find(JsonNode page, String id) {
    Assertions.assertNotNull(page, "listing has no JSON body");
    Assertions.assertTrue(page.path("resources").isArray(), "listing has no resources array: " + page);
    JsonNode found = null;
    for (JsonNode resource : page.path("resources")) {
      Assertions.assertTrue(resource.path("@id").isTextual() && !resource.path("@id").asText().isBlank(),
          "listing row has no identity: " + resource);
      if (id.equals(resource.path("@id").asText())) found = resource;
    }
    return found;
  }
}
