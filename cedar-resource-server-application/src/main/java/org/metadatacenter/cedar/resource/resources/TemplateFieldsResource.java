package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.CharEncoding;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarFieldId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-fields")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateFieldsResource extends AbstractResourceServerResource {

  public TemplateFieldsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplateField(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.FIELD, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    userMustHaveReadAccessToArtifact(c, fid);
    return executeResourceGetByProxyFromArtifactServer(CedarResourceType.FIELD, id, c);
  }


  @POST
  @Timed
  @Path("/{id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML})
  public Response downloadTemplateField(@PathParam(PP_ID) String id,
                                   @HeaderParam("Accept") String acceptHeader,
                                   @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    userMustHaveReadAccessToArtifact(c, fid);

    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.FIELD, fid);
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    // If error while retrieving artifact, re-run and return proxy call directly
    if (proxyResponse.getStatusLine().getStatusCode() != Response.Status.OK.getStatusCode()) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.FIELD, id, c);
    }
    HttpEntity entity = proxyResponse.getEntity();
    JsonNode fieldNode = null;

    try {
      String fieldSource = EntityUtils.toString(entity, CharEncoding.UTF_8);
      fieldNode = JsonMapper.MAPPER.readTree(fieldSource);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    String fieldUUID = linkedDataUtil.getUUID(id, CedarResourceType.FIELD);

    // Handle JSON
    if (acceptHeader == null || acceptHeader.isEmpty() || acceptHeader.contains(MediaType.APPLICATION_JSON) || acceptHeader.contains("*/*")) {
      String fileName = fieldUUID + ".json";
      return CedarResponse.ok()
          .type(MediaType.APPLICATION_JSON)
          .contentDispositionAttachment(fileName)
          .entity(fieldNode)
          .build();
    }
    // Handle YAML
    if (acceptHeader.contains(HttpConstants.CONTENT_TYPE_APPLICATION_YAML)) {
      String fileName = fieldUUID + ".yaml";
      JsonArtifactReader reader = new JsonArtifactReader();
      Artifact modelArtifact = reader.readFieldSchemaArtifact((ObjectNode) fieldNode);
      String content = YamlSerializer.getYAML(modelArtifact, compactParam.isPresent() && compactParam.get(), true);
      return CedarResponse.ok()
          .type(HttpConstants.CONTENT_TYPE_APPLICATION_YAML)
          .contentDispositionAttachment(fileName)
          .entity(content)
          .build();
    }
    // Unknown accept header
    return CedarResponse.badRequest()
        .errorMessage("You passed an invalid Accept header: '" + acceptHeader + "'")
        .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
        .parameter(HttpConstants.HTTP_HEADER_ACCEPT, acceptHeader)
        .parameter("allowed Accept headers", Arrays.toString(new String[]{MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML}))
        .build();
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateFieldDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return getDetails(c, fid);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateField(@PathParam(PP_ID) String id, @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);
    CedarFieldId fid = CedarFieldId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.FIELD, fid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_DELETE);
    CedarFieldId fid = CedarFieldId.build(id);

    return executeArtifactDelete(c, CedarResourceType.FIELD, fid);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateFieldPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateResourcePermissionsResponse(c, fid);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateFieldPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);
    CedarFieldId fid = CedarFieldId.build(id);

    return updateResourcePermissions(c, fid);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateFieldInstanceReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateArtifactReportResponse(c, fid);
  }

  @GET
  @Timed
  @Path("/{id}/versions")
  public Response getTemplateFieldVersions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateNodeVersionsResponse(c, fid);
  }

}
