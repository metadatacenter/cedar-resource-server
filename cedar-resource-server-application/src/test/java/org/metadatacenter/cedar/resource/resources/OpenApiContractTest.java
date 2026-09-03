package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.metadatacenter.util.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiContractTest {

  private static final String RESOURCE_LIST = "#/components/schemas/ResourceListResponse";

  @Test
  void searchAndFolderListingsPublishTheirEnvelope() throws IOException {
    JsonNode spec = readSpec();
    assertResponseSchema(spec, "/search", RESOURCE_LIST);
    assertResponseSchema(spec, "/search-deep", RESOURCE_LIST);
    assertResponseSchema(spec, "/folders/{folder_id}/contents", RESOURCE_LIST);

    JsonNode envelope = spec.at("/components/schemas/ResourceListResponse");
    assertTrue(envelope.path("additionalProperties").asBoolean());
    for (String property : new String[]{"resources", "pathInfo", "totalCount", "currentOffset", "paging",
        "continuation", "nodeListQueryType", "categoryName", "categoryPath", "request", "@context"}) {
      assertTrue(envelope.path("properties").has(property), "missing response property " + property);
    }
  }

  private static void assertResponseSchema(JsonNode spec, String path, String expectedRef) {
    assertEquals(expectedRef,
        spec.path("paths").path(path).path("get").path("responses").path("200")
            .path("content").path("application/json").path("schema").path("$ref").asText());
  }

  private static JsonNode readSpec() throws IOException {
    try (InputStream input = OpenApiContractTest.class.getResourceAsStream("/assets/swagger-api/swagger.json")) {
      assertNotNull(input, "generated OpenAPI document");
      return JsonMapper.MAPPER.readTree(input);
    }
  }
}
