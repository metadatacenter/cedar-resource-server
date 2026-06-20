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
import org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateInstance;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarTemplateInstanceId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.proxy.ArtifactProxy;
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

import static org.metadatacenter.constant.CedarPathParameters.PP_TEMPLATE_INSTANCE_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-instances")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/template-instances", tags = "Template Instances", authorizations = {@Authorization("api_key")})
public class TemplateInstancesResource extends AbstractResourceServerResource {

  public TemplateInstancesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @ApiOperation(value = "Create a template instance", notes = "Create a template instance.", code = 201, response = TemplateInstance.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template_instance", value = "The template instance to be created", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateInstance", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 201, message = "A template instance", response = TemplateInstance.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response createTemplateInstance(
      @ApiParam(value = "Folder identifier. The artifact will be created in this folder. The user must have write "
          + "access to the folder. If not provided, the artifact will be created in the user's home folder.")
      @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.INSTANCE, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{template_instance_id}")
  @ApiOperation(value = "Get a template instance", notes = "Get a template instance.", response = TemplateInstance.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template instance", response = TemplateInstance.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplateInstance(
      @ApiParam(value = "Template Instance identifier. Example: https://repo.metadatacenter.org/template-instances/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_TEMPLATE_INSTANCE_ID) String id,
      @ApiParam(value = "Output format type to display the content of the template instance. The allowed values are: "
          + "'jsonld', 'json', 'rdf-nquad'.")
      @QueryParam(QP_FORMAT) Optional<String> format) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    userMustHaveReadAccessToArtifact(c, tiid);
    return ArtifactProxy.executeResourceGetByProxyFromArtifactServer(microserviceUrlUtil, response, CedarResourceType.INSTANCE, id, format, c);
  }


  @POST
  @Timed
  @Path("/{template_instance_id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML})
  @ApiOperation(value = "Download a template instance", notes = "Download a template instance as JSON or YAML, selected "
      + "via the Accept header.")
  @ApiResponses({
      @ApiResponse(code = 200, message = "The template instance content as an attachment"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response downloadTemplateInstance(
      @ApiParam(value = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id,
      @ApiParam(value = "Desired output format: 'application/json' or 'application/yaml'.")
      @HeaderParam("Accept") String acceptHeader,
      @ApiParam(value = "When downloading YAML, produce a compact representation.")
      @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    userMustHaveReadAccessToArtifact(c, tiid);

    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.INSTANCE, tiid);
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    // If error while retrieving artifact, re-run and return proxy call directly
    if (proxyResponse.getStatusLine().getStatusCode() != Response.Status.OK.getStatusCode()) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.INSTANCE, id, c);
    }
    HttpEntity entity = proxyResponse.getEntity();
    JsonNode instanceNode = null;

    try {
      String instanceSource = EntityUtils.toString(entity, CharEncoding.UTF_8);
      instanceNode = JsonMapper.MAPPER.readTree(instanceSource);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    String instanceUUID = linkedDataUtil.getUUID(id, CedarResourceType.INSTANCE);

    // Handle JSON
    if (acceptHeader == null || acceptHeader.isEmpty() || acceptHeader.contains(MediaType.APPLICATION_JSON) || acceptHeader.contains("*/*")) {
      String fileName = instanceUUID + ".json";
      return CedarResponse.ok()
          .type(MediaType.APPLICATION_JSON)
          .contentDispositionAttachment(fileName)
          .entity(instanceNode)
          .build();
    }
    // Handle YAML
    if (acceptHeader.contains(HttpConstants.CONTENT_TYPE_APPLICATION_YAML)) {
      String fileName = instanceUUID + ".yaml";
      JsonArtifactReader reader = new JsonArtifactReader();
      Artifact modelArtifact = reader.readTemplateInstanceArtifact((ObjectNode) instanceNode);
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
  @Path("/{template_instance_id}/details")
  @ApiOperation(value = "Get details of a template instance", notes = "Get details of a template instance.",
      tags = {"Template Instances", "Resource Details"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findTemplateInstanceDetails(
      @ApiParam(value = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return getDetails(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{template_instance_id}")
  @ApiOperation(value = "Update a template instance", notes = "Update a template instance.", response = TemplateInstance.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "template_instance", value = "The template instance to be updated", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateInstance", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "A template instance", response = TemplateInstance.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplateInstance(
      @ApiParam(value = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id,
      @ApiParam(value = "Folder identifier.") @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.INSTANCE, tiid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{template_instance_id}")
  @ApiOperation(value = "Delete a template instance", notes = "Delete a template instance.")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Successful operation (no content)"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response deleteTemplateInstance(
      @ApiParam(value = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_DELETE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return executeArtifactDelete(c, CedarResourceType.INSTANCE, tiid);
  }

  @GET
  @Timed
  @Path("/{template_instance_id}/permissions")
  @ApiOperation(value = "Get permissions of a template instance", notes = "Get permissions of a template instance.",
      tags = {"Template Instances", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateInstancePermissions(
      @ApiParam(value = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateResourcePermissionsResponse(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{template_instance_id}/permissions")
  @ApiOperation(value = "Update permissions of a template instance", notes = "Update permissions of a template instance.",
      tags = {"Template Instances", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateTemplateInstancePermissions(
      @ApiParam(value = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return updateResourcePermissions(c, tiid);
  }

  @GET
  @Timed
  @Path("/{template_instance_id}/report")
  @ApiOperation(value = "Get report of a template instance", notes = "Get report of a template instance.",
      tags = {"Template Instances", "Resource Report"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getTemplateInstanceReport(
      @ApiParam(value = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateArtifactReportResponse(c, tiid);
  }

}
