package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.util.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterServiceResponseCompatibilityTest {

  @Test
  void resourceReaderAcceptsAPropertyAddedByANewerService() throws Exception {
    FolderServerFolder produced = new FolderServerFolder();
    produced.setId("https://repo.example/folders/one");
    produced.setName("Examples");
    ObjectNode json = (ObjectNode) JsonMapper.MAPPER.valueToTree(produced);
    json.put("futureResponseProperty", true);

    BasicClassicHttpResponse response = new BasicClassicHttpResponse(HttpStatus.SC_OK);
    response.setEntity(new StringEntity(
        JsonMapper.MAPPER.writeValueAsString(json), ContentType.APPLICATION_JSON));

    FolderServerFolder consumed = AbstractResourceServerResource.deserializeResource(
        response, FolderServerFolder.class);

    assertEquals(produced.getId(), consumed.getId());
    assertEquals(produced.getName(), consumed.getName());
  }
}
