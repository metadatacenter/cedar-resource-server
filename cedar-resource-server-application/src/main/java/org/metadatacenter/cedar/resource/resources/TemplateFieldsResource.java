package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.apache.commons.codec.CharEncoding;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateField;
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

import static org.metadatacenter.constant.CedarPathParameters.PP_TEMPLATE_FIELD_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-fields")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/template-fields", tags = "Template Fields", authorizations = {@Authorization("api_key")})
public class TemplateFieldsResource extends AbstractResourceServerResource {

  public TemplateFieldsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @ApiOperation(value = "Create a template field", notes = "Create a template field.", code = 201, response = TemplateField.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template_field", value = "The template field to be created", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateField", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 201, message = "A template field", response = TemplateField.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response createTemplateField(
      @ApiParam(value = "Folder identifier. The artifact will be created in this folder. The user must have write "
          + "access to the folder. If not provided, the artifact will be created in the user's home folder.")
      @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.FIELD, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{template_field_id}")
  @ApiOperation(value = "Get a template field", notes = "Get a template field.", response = TemplateField.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template field", response = TemplateField.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplateField(
      @ApiParam(value = "Template Field identifier. Example: https://repo.metadatacenter.org/template-fields/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_TEMPLATE_FIELD_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    userMustHaveReadAccessToArtifact(c, fid);
    return executeResourceGetByProxyFromArtifactServer(CedarResourceType.FIELD, id, c);
  }


  @POST
  @Timed
  @Path("/{template_field_id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML})
  @ApiOperation(value = "Download a template field", notes = "Download a template field as JSON or YAML, selected via "
      + "the Accept header.")
  @ApiResponses({
      @ApiResponse(code = 200, message = "The template field content as an attachment"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response downloadTemplateField(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id,
      @ApiParam(value = "Desired output format: 'application/json' or 'application/yaml'.")
      @HeaderParam("Accept") String acceptHeader,
      @ApiParam(value = "When downloading YAML, produce a compact representation.")
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
  @Path("/{template_field_id}/details")
  @ApiOperation(value = "Get details of a template field", notes = "Get details of a template field.",
      tags = {"Template Fields", "Resource Details"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplateFieldDetails(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return getDetails(c, fid);
  }

  @PUT
  @Timed
  @Path("/{template_field_id}")
  @ApiOperation(value = "Update a template field", notes = "Update a template field.", response = TemplateField.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template_field", value = "The template field to be updated", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateField", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template field", response = TemplateField.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplateField(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id,
      @ApiParam(value = "Folder identifier.") @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);
    CedarFieldId fid = CedarFieldId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.FIELD, fid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{template_field_id}")
  @ApiOperation(value = "Delete a template field", notes = "Delete a template field.")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Successful operation (no content)"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response deleteTemplateField(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_DELETE);
    CedarFieldId fid = CedarFieldId.build(id);

    return executeArtifactDelete(c, CedarResourceType.FIELD, fid);
  }

  @GET
  @Timed
  @Path("/{template_field_id}/permissions")
  @ApiOperation(value = "Get permissions of a template field", notes = "Get permissions of a template field.",
      tags = {"Template Fields", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateFieldPermissions(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateResourcePermissionsResponse(c, fid);
  }

  @PUT
  @Timed
  @Path("/{template_field_id}/permissions")
  @ApiOperation(value = "Update permissions of a template field", notes = "Update permissions of a template field.",
      tags = {"Template Fields", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplateFieldPermissions(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);
    CedarFieldId fid = CedarFieldId.build(id);

    return updateResourcePermissions(c, fid);
  }

  @GET
  @Timed
  @Path("/{template_field_id}/report")
  @ApiOperation(value = "Get report of a template field", notes = "Get report of a template field.",
      tags = {"Template Fields", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateFieldInstanceReport(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateArtifactReportResponse(c, fid);
  }

  @GET
  @Timed
  @Path("/{template_field_id}/versions")
  @ApiOperation(value = "Get a list of versions of a template field", notes = "Get a list of versions of a template field.",
      tags = {"Template Fields", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateFieldVersions(
      @ApiParam(value = "Template Field identifier.", required = true) @PathParam(PP_TEMPLATE_FIELD_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateNodeVersionsResponse(c, fid);
  }

}
