package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ModelNodeNames;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandAnnotationsResource extends AbstractResourceServerResource {

  public CommandAnnotationsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/annotations/doi")
  public Response setDOI() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String requestContent = requestBody.asJsonString();
    String id = requestBody.get("@id").stringValue();
    String doiInRequest = requestBody.get("doi").stringValue();
    CedarUntypedArtifactId artifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToArtifact(c, artifactId);

    FolderServerArtifact folderServerOldResource = folderSession.findArtifactById(artifactId);

    CedarResourceType resourceType = folderServerOldResource.getType();

    if (doiInRequest != null && !resourceType.supportsDOI()) {
      return CedarResponse.badRequest()
          .errorMessage("The doi is not supported by the given resource type")
          .errorKey(CedarErrorKey.DOI_NOT_SUPPORTED_BY_RESOURCE_TYPE)
          .parameter("resourceType", resourceType)
          .build();
    }

    String existingDOI = folderServerOldResource.getDOI();
    if (existingDOI != null) {
      if (!existingDOI.equals(doiInRequest)) {
        return CedarResponse.badRequest()
            .errorMessage("The doi can not be altered")
            .errorKey(CedarErrorKey.DOI_CAN_NOT_BE_ALTERED)
            .parameter("existingDOI", existingDOI)
            .parameter("doi", doiInRequest)
            .build();
      }
    }

    String artifactGetUrl = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, artifactId.getId(), Optional.empty());
    JsonNode oldArtifactContent = ProxyUtil.proxyGetBodyAsJsonNode(artifactGetUrl, c);
    ObjectNode objectNode = (ObjectNode) oldArtifactContent;
    ObjectNode annotationsNode;
    if (objectNode.has(ModelNodeNames.ANNOTATIONS) && objectNode.get(ModelNodeNames.ANNOTATIONS).isObject()) {
      annotationsNode = (ObjectNode) objectNode.get(ModelNodeNames.ANNOTATIONS);
    } else {
      annotationsNode = objectNode.putObject(ModelNodeNames.ANNOTATIONS);
    }
    ObjectNode doiNode = JsonMapper.MAPPER.createObjectNode();
    doiNode.put(ModelNodeNames.JSON_LD_ID, doiInRequest);
    annotationsNode.set(ModelNodeNames.DATACITE_DOI_URI, doiNode);

    try {
      ProxyUtil.proxyPut(artifactGetUrl, c, JsonMapper.MAPPER.writeValueAsString(objectNode));
    } catch (JsonProcessingException e) {
      throw new CedarProcessingException(e);
    }

    Map<NodeProperty, String> updateFields = new HashMap<>();
    updateFields.put(NodeProperty.DOI, doiInRequest);
    FolderServerArtifact updatedResource = folderSession.updateArtifactById(artifactId, resourceType, updateFields);

    return Response.ok().entity(updatedResource).build();
  }


}
