package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
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
import org.metadatacenter.util.ModelUtil;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandAnnotationsResource extends AbstractResourceServerResource {

  public CommandAnnotationsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/annotations/doi")
  @Operation(summary = "Set the DOI of an artifact", description = "Set the DOI annotation of an artifact. The user must have 'write' access to the artifact. The "
          + "resource type must support DOIs, and an existing DOI can not be altered.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response setDOI() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String requestContent = requestBody.asJsonString();
    String id = requestBody.get("@id").stringValue();
    String doiInRequest = requestBody.get("doi").stringValue();
    CedarUntypedArtifactId artifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);

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
    ClassicHttpResponse artifactGetResponse = ProxyUtil.proxyGet(artifactGetUrl, c);
    if (Response.Status.Family.familyOf(artifactGetResponse.getCode()) != Response.Status.Family.SUCCESSFUL) {
      return generateStatusResponse(artifactGetResponse);
    }
    Header revisionHeader = artifactGetResponse.getFirstHeader(jakarta.ws.rs.core.HttpHeaders.ETAG);
    String expectedEtag = revisionHeader == null ? null : revisionHeader.getValue();
    JsonNode oldArtifactContent;
    try {
      oldArtifactContent = JsonMapper.MAPPER.readTree(
          EntityUtils.toString(artifactGetResponse.getEntity(), StandardCharsets.UTF_8));
    } catch (IOException | ParseException e) {
      throw new CedarProcessingException(e);
    }
    String artifactDOI = ModelUtil.extractDOIFromResource(oldArtifactContent).getValue();
    if (artifactDOI != null && !artifactDOI.equals(doiInRequest)) {
      return doiCanNotBeAltered(artifactDOI, doiInRequest);
    }

    Map<NodeProperty, String> updateFields = new HashMap<>();
    updateFields.put(NodeProperty.DOI, doiInRequest);

    // A prior request may have committed the document but not its graph projection. Repeating the
    // same DOI repairs that projection without replacing the document or incrementing its revision.
    if (artifactDOI != null) {
      if (existingDOI != null) {
        return Response.ok().entity(folderServerOldResource).build();
      }
      FolderServerArtifact repaired = folderSession.updateArtifactById(artifactId, resourceType, updateFields);
      return repaired == null ? CedarResponse.internalServerError().build() : Response.ok().entity(repaired).build();
    }

    String oldArtifactContentJson = oldArtifactContent.toString();
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

    boolean artifactUpdated = false;
    boolean graphUpdated = false;
    String replacementEtag = null;
    ArtifactPreImage artifactPreImage = new ArtifactPreImage(oldArtifactContentJson, expectedEtag);
    try {
      var artifactPutResponse = ProxyUtil.proxyPut(artifactGetUrl, c,
          JsonMapper.MAPPER.writeValueAsString(objectNode), expectedEtag);
      ProxyUtil.proxyResponseHeaders(artifactPutResponse, response);
      if (Response.Status.Family.familyOf(artifactPutResponse.getCode()) != Response.Status.Family.SUCCESSFUL) {
        return generateStatusResponse(artifactPutResponse);
      }
      artifactUpdated = true;
      replacementEtag = headerValue(artifactPutResponse, jakarta.ws.rs.core.HttpHeaders.ETAG);

      FolderServerArtifact updatedResource = folderSession.updateArtifactById(artifactId, resourceType, updateFields);
      if (updatedResource == null) {
        return CedarResponse.internalServerError().build();
      }
      graphUpdated = true;
      return Response.ok().entity(updatedResource).build();
    } catch (JsonProcessingException e) {
      throw new CedarProcessingException(e);
    } finally {
      if (artifactUpdated && !graphUpdated) {
        restoreArtifactAfterFailedGraphUpdate(c, resourceType, artifactId, artifactPreImage, replacementEtag,
            false);
      }
    }
  }

  private Response doiCanNotBeAltered(String existingDOI, String requestedDOI) {
    return CedarResponse.badRequest()
        .errorMessage("The doi can not be altered")
        .errorKey(CedarErrorKey.DOI_CAN_NOT_BE_ALTERED)
        .parameter("existingDOI", existingDOI)
        .parameter("doi", requestedDOI)
        .build();
  }


}
