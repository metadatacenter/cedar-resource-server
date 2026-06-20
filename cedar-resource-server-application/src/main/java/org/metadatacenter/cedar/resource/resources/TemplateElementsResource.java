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
import org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateElement;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarElementId;
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

import static org.metadatacenter.constant.CedarPathParameters.PP_TEMPLATE_ELEMENT_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-elements")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/template-elements", tags = "Template Elements", authorizations = {@Authorization("api_key")})
public class TemplateElementsResource extends AbstractResourceServerResource {

  public TemplateElementsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @ApiOperation(value = "Create a template element", notes = "Create a template element.", code = 201, response = TemplateElement.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template_element", value = "The template element to be created", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateElement", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 201, message = "A template element", response = TemplateElement.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response createTemplateElement(
      @ApiParam(value = "Folder identifier. The artifact will be created in this folder. The user must have write "
          + "access to the folder. If not provided, the artifact will be created in the user's home folder.")
      @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.ELEMENT, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{template_element_id}")
  @ApiOperation(value = "Get a template element", notes = "Get a template element.", response = TemplateElement.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template element", response = TemplateElement.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplateElement(
      @ApiParam(value = "Template Element identifier. Example: https://repo.metadatacenter.org/template-elements/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    userMustHaveReadAccessToArtifact(c, eid);
    return executeResourceGetByProxyFromArtifactServer(CedarResourceType.ELEMENT, id, c);
  }

  @POST
  @Timed
  @Path("/{template_element_id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML})
  @ApiOperation(value = "Download a template element", notes = "Download a template element as JSON or YAML, selected "
      + "via the Accept header.")
  @ApiResponses({
      @ApiResponse(code = 200, message = "The template element content as an attachment"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response downloadTemplateElement(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id,
      @ApiParam(value = "Desired output format: 'application/json' or 'application/yaml'.")
      @HeaderParam("Accept") String acceptHeader,
      @ApiParam(value = "When downloading YAML, produce a compact representation.")
      @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    userMustHaveReadAccessToArtifact(c, eid);

    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.ELEMENT, eid);
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    // If error while retrieving artifact, re-run and return proxy call directly
    if (proxyResponse.getStatusLine().getStatusCode() != Response.Status.OK.getStatusCode()) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.ELEMENT, id, c);
    }
    HttpEntity entity = proxyResponse.getEntity();
    JsonNode elementNode = null;

    try {
      String elementSource = EntityUtils.toString(entity, CharEncoding.UTF_8);
      elementNode = JsonMapper.MAPPER.readTree(elementSource);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    String elementUUID = linkedDataUtil.getUUID(id, CedarResourceType.ELEMENT);

    // Handle JSON
    if (acceptHeader == null || acceptHeader.isEmpty() || acceptHeader.contains(MediaType.APPLICATION_JSON) || acceptHeader.contains("*/*")) {
      String fileName = elementUUID + ".json";
      return CedarResponse.ok()
          .type(MediaType.APPLICATION_JSON)
          .contentDispositionAttachment(fileName)
          .entity(elementNode)
          .build();
    }
    // Handle YAML
    if (acceptHeader.contains(HttpConstants.CONTENT_TYPE_APPLICATION_YAML)) {
      String fileName = elementUUID + ".yaml";
      JsonArtifactReader reader = new JsonArtifactReader();
      Artifact modelArtifact = reader.readElementSchemaArtifact((ObjectNode) elementNode);
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
  @Path("/{template_element_id}/details")
  @ApiOperation(value = "Get details of a template element", notes = "Get details of a template element.",
      tags = {"Template Elements", "Resource Details"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplateElementDetails(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return getDetails(c, eid);
  }

  @PUT
  @Timed
  @Path("/{template_element_id}")
  @ApiOperation(value = "Update a template element", notes = "Update a template element.", response = TemplateElement.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template_element", value = "The template element to be updated", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateElement", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template element", response = TemplateElement.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplateElement(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id,
      @ApiParam(value = "Folder identifier.") @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_UPDATE);
    CedarElementId eid = CedarElementId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.ELEMENT, eid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{template_element_id}")
  @ApiOperation(value = "Delete a template element", notes = "Delete a template element.")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Successful operation (no content)"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response deleteTemplateElement(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_DELETE);
    CedarElementId eid = CedarElementId.build(id);

    return executeArtifactDelete(c, CedarResourceType.ELEMENT, eid);
  }

  @GET
  @Timed
  @Path("/{template_element_id}/permissions")
  @ApiOperation(value = "Get permissions of a template element", notes = "Get permissions of a template element.",
      tags = {"Template Elements", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateElementPermissions(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return generateResourcePermissionsResponse(c, eid);
  }

  @PUT
  @Timed
  @Path("/{template_element_id}/permissions")
  @ApiOperation(value = "Update permissions of a template element", notes = "Update permissions of a template element.",
      tags = {"Template Elements", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplateElementPermissions(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_UPDATE);
    CedarElementId eid = CedarElementId.build(id);

    return updateResourcePermissions(c, eid);
  }

  @GET
  @Timed
  @Path("/{template_element_id}/report")
  @ApiOperation(value = "Get report of a template element", notes = "Get report of a template element.",
      tags = {"Template Elements", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateElementInstanceReport(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return generateArtifactReportResponse(c, eid);
  }

  @GET
  @Timed
  @Path("/{template_element_id}/versions")
  @ApiOperation(value = "Get a list of versions of a template element", notes = "Get a list of versions of a template element.",
      tags = {"Template Elements", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateElementVersions(
      @ApiParam(value = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return generateNodeVersionsResponse(c, eid);
  }

}
