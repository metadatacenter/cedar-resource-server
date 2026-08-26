package org.metadatacenter.cedar.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.metadatacenter.util.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArtifactServerUtilTest {

  @Test
  void returnsStatusOnlyWhenArtifactServerResponseIsNotJson() {
    BasicClassicHttpResponse artifactServerResponse = new BasicClassicHttpResponse(422);
    artifactServerResponse.setEntity(new StringEntity("validation failed", ContentType.TEXT_PLAIN));
    artifactServerResponse.addHeader(HttpHeaders.ETAG, "upstream-etag");

    Response response = ArtifactServerUtil.buildPutResponse(artifactServerResponse);

    assertEquals(422, response.getStatus());
    assertFalse(response.hasEntity());
    assertNull(response.getHeaderString(HttpHeaders.ETAG));
  }

  @Test
  void retainsJsonBodyAndEtagForValidArtifactServerResponse() throws Exception {
    BasicClassicHttpResponse artifactServerResponse = new BasicClassicHttpResponse(200);
    artifactServerResponse.setEntity(new StringEntity("{\"status\":\"ok\"}", ContentType.APPLICATION_JSON));
    artifactServerResponse.addHeader(HttpHeaders.ETAG, "upstream-etag");

    Response response = ArtifactServerUtil.buildPutResponse(artifactServerResponse);

    assertEquals(200, response.getStatus());
    assertEquals(JsonMapper.MAPPER.readTree("{\"status\":\"ok\"}"), (JsonNode) response.getEntity());
    assertEquals("upstream-etag", response.getHeaderString(HttpHeaders.ETAG));
  }
}
