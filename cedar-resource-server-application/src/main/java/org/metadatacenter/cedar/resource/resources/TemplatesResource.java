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
import org.metadatacenter.cedar.resource.resources.swaggermodel.Template;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarTemplateId;
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

import static org.metadatacenter.constant.CedarPathParameters.PP_TEMPLATE_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/templates", tags = "Templates", authorizations = {@Authorization("api_key")})
public class TemplatesResource extends AbstractResourceServerResource {

  public TemplatesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @ApiOperation(value = "Create a template", notes = "Create a template.", code = 201, response = Template.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template", value = "The template to be created", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.Template", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 201, message = "A template", response = Template.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response createTemplate(
      @ApiParam(value = "Folder identifier. The artifact will be created in this folder. The user must have write "
          + "access to the folder. If not provided, the artifact will be created in the user's home folder.")
      @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.TEMPLATE, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{template_id}")
  @ApiOperation(value = "Get a template", notes = "Get a template.", response = Template.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template", response = Template.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplate(
      @ApiParam(value = "Template identifier. Example: https://repo.metadatacenter.org/templates/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveReadAccessToArtifact(c, tid);
    return executeResourceGetByProxyFromArtifactServer(CedarResourceType.TEMPLATE, id, c);
  }

  @POST
  @Timed
  @Path("/{template_id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML})
  @ApiOperation(value = "Download a template", notes = "Download a template as JSON or YAML, selected via the Accept "
      + "header.")
  @ApiResponses({
      @ApiResponse(code = 200, message = "The template content as an attachment"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response downloadTemplate(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id,
      @ApiParam(value = "Desired output format: 'application/json' or 'application/yaml'.")
      @HeaderParam("Accept") String acceptHeader,
      @ApiParam(value = "When downloading YAML, produce a compact representation.")
      @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveReadAccessToArtifact(c, tid);

    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.TEMPLATE, tid);
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    // If error while retrieving artifact, re-run and return proxy call directly
    if (proxyResponse.getStatusLine().getStatusCode() != Response.Status.OK.getStatusCode()) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.TEMPLATE, id, c);
    }
    HttpEntity entity = proxyResponse.getEntity();
    JsonNode templateNode = null;

    try {
      String templateSource = EntityUtils.toString(entity, CharEncoding.UTF_8);
      templateNode = JsonMapper.MAPPER.readTree(templateSource);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    String templateUUID = linkedDataUtil.getUUID(id, CedarResourceType.TEMPLATE);

    // Handle JSON
    if (acceptHeader == null || acceptHeader.isEmpty() || acceptHeader.contains(MediaType.APPLICATION_JSON) || acceptHeader.contains("*/*")) {
      String fileName = templateUUID + ".json";
      return CedarResponse.ok()
          .type(MediaType.APPLICATION_JSON)
          .contentDispositionAttachment(fileName)
          .entity(templateNode)
          .build();
    }
    // Handle YAML
    if (acceptHeader.contains(HttpConstants.CONTENT_TYPE_APPLICATION_YAML)) {
      String fileName = templateUUID + ".yaml";
      JsonArtifactReader reader = new JsonArtifactReader();
      Artifact modelArtifact = reader.readTemplateSchemaArtifact((ObjectNode) templateNode);
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
  @Path("/{template_id}/details")
  @ApiOperation(value = "Get details of a template", notes = "Get details of a template.", tags = {"Templates", "Resource Details"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplateDetails(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return getDetails(c, tid);
  }

  @PUT
  @Timed
  @Path("/{template_id}")
  @ApiOperation(value = "Update a template", notes = "Update a template.", response = Template.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template", value = "The template to be updated", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.Template", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template", response = Template.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplate(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id,
      @ApiParam(value = "Folder identifier.") @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.TEMPLATE, tid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{template_id}")
  @ApiOperation(value = "Delete a template", notes = "Delete a template.")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Successful operation (no content)"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response deleteTemplate(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_DELETE);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return executeArtifactDelete(c, CedarResourceType.TEMPLATE, tid);
  }

  @GET
  @Timed
  @Path("/{template_id}/permissions")
  @ApiOperation(value = "Get permissions of a template", notes = "Get permissions of a template.", tags = {"Templates", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplatePermissions(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return generateResourcePermissionsResponse(c, tid);
  }

  @PUT
  @Timed
  @Path("/{template_id}/permissions")
  @ApiOperation(value = "Update permissions of a template", notes = "Update permissions of a template.", tags = {"Templates", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplatePermissions(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return updateResourcePermissions(c, tid);
  }

  @GET
  @Timed
  @Path("/{template_id}/report")
  @ApiOperation(value = "Get report of a template", notes = "Get report of a template.", tags = {"Templates", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateReport(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return generateArtifactReportResponse(c, tid);
  }

  @GET
  @Timed
  @Path("/{template_id}/versions")
  @ApiOperation(value = "Get a list of versions of a template", notes = "Get a list of versions of a template.",
      tags = {"Templates", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateVersions(
      @ApiParam(value = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return generateNodeVersionsResponse(c, tid);
  }

}
