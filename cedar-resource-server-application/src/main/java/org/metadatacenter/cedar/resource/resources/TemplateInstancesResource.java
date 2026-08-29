package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.codec.CharEncoding;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
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
import org.metadatacenter.util.artifact.ArtifactYamlTranscoder;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_TEMPLATE_INSTANCE_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_VERBATIM;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-instances")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Template Instances")
@SecurityRequirement(name = "api_key")
public class TemplateInstancesResource extends AbstractResourceServerResource {

  public TemplateInstancesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Consumes({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Create a template instance", description = "Create a template instance. The body can be JSON or "
      + "YAML, selected via the Content-Type header. A YAML body must be the full or minimal form: "
      + "the compact form is a lossy read-time convenience and is rejected.")
  @RequestBody(description = "The template instance to be created", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateInstance.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "A template instance", content = @Content(schema = @Schema(implementation = TemplateInstance.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response createTemplateInstance(
      @Parameter(description = "Folder identifier. The artifact will be created in this folder. The user must have write "
          + "access to the folder. If not provided, the artifact will be created in the user's home folder.")
      @QueryParam(QP_FOLDER_ID) Optional<String> folderId,
      @Parameter(description = "Not supported on write operations; write responses always render the full form.")
      @QueryParam("compact") Optional<Boolean> compactParam,
      @Parameter(hidden = true) String requestBody) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE);
    rejectCompactOnWriteOperations(compactParam);
    String content = artifactRequestBodyAsJson(requestBody, CedarResourceType.INSTANCE, templateResolverFor(c));
    Response artifactResponse = executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.INSTANCE, Optional.empty(), folderId, content);
    return negotiateArtifactResponse(artifactResponse, CedarResourceType.INSTANCE);
  }

  @GET
  @Timed
  @Path("/{template_instance_id}")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Get a template instance", description = "Get a template instance. YAML can be requested via the "
      + "Accept header; the format query parameter takes precedence.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A template instance", content = @Content(schema = @Schema(implementation = TemplateInstance.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findTemplateInstance(
      @Parameter(description = "Template Instance identifier. Example: https://repo.metadatacenter.org/template-instances/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_TEMPLATE_INSTANCE_ID) String id,
      @Parameter(description = "Output format type to display the content of the template instance. The allowed values are: "
          + "'jsonld', 'json', 'rdf-nquad'.")
      @QueryParam(QP_FORMAT) Optional<String> format,
      @Parameter(description = "When requesting YAML, produce a compact representation.")
      @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    userMustHaveReadAccessToArtifact(c, tiid);
    if (format.isEmpty()) {
      return executeArtifactGetNegotiated(c, CedarResourceType.INSTANCE, tiid, compactParam);
    }
    return ArtifactProxy.executeResourceGetByProxyFromArtifactServer(microserviceUrlUtil, response, CedarResourceType.INSTANCE, id, format, c);
  }


  @POST
  @Timed
  @Path("/{template_instance_id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Download a template instance", description = "Download a template instance as JSON or YAML, selected "
      + "via the Accept header.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template instance content as an attachment"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response downloadTemplateInstance(
      @Parameter(description = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id,
      @Parameter(description = "Desired output format: 'application/json' or 'application/yaml'.")
      @HeaderParam("Accept") String acceptHeader,
      @Parameter(description = "When downloading YAML, produce a compact representation.")
      @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    userMustHaveReadAccessToArtifact(c, tiid);

    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.INSTANCE, tiid);
    ClassicHttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    // If error while retrieving artifact, re-run and return proxy call directly
    if (proxyResponse.getCode() != Response.Status.OK.getStatusCode()) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.INSTANCE, id, c);
    }
    HttpEntity entity = proxyResponse.getEntity();
    JsonNode instanceNode = null;

    try {
      String instanceSource = EntityUtils.toString(entity, CharEncoding.UTF_8);
      instanceNode = JsonMapper.MAPPER.readTree(instanceSource);
    } catch (IOException | ParseException e) {
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
    if (acceptHeader.contains("yaml")) {  // matches both application/yaml and application/x-yaml
      String fileName = instanceUUID + ".yaml";
      String content = ArtifactYamlTranscoder.jsonToYaml(instanceNode, CedarResourceType.INSTANCE, compactParam.isPresent() && compactParam.get());
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
  @Operation(summary = "Get details of a template instance", description = "Get details of a template instance.", tags = {"Template Instances", "Resource Details"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findTemplateInstanceDetails(
      @Parameter(description = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return getDetails(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{template_instance_id}")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Consumes({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Update a template instance", description = "Update a template instance. The body can be JSON or "
      + "YAML, selected via the Content-Type header. A YAML body must be the full or minimal form: "
      + "the compact form is a lossy read-time convenience and is rejected.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatchForCreateOrReplace"))
  @RequestBody(description = "The template instance to be updated", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateInstance.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A template instance", content = @Content(schema = @Schema(implementation = TemplateInstance.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "201", description = "A template instance created with the supplied identifier",
          content = @Content(schema = @Schema(implementation = TemplateInstance.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response updateTemplateInstance(
      @Parameter(description = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id,
      @Parameter(description = "Folder identifier.") @QueryParam(QP_FOLDER_ID) Optional<String> folderId,
      @Parameter(description = "Not supported on write operations; write responses always render the full form.")
      @QueryParam("compact") Optional<Boolean> compactParam,
      @Parameter(description = "Admin only. Replace the artifact with exactly the document supplied: no provenance stamped, no child identifier minted. The artifact must exist and the body must be JSON.")
      @QueryParam(QP_VERBATIM) Optional<Boolean> verbatimParam,
      @Parameter(hidden = true) String requestBody) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    rejectCompactOnWriteOperations(compactParam);
    boolean verbatim = verbatimParam != null && verbatimParam.isPresent() && verbatimParam.get();
    if (verbatim) {
      c.must(c.user()).have(CedarPermission.WRITE_ARTIFACT_VERBATIM);
    }
    rejectYamlVerbatimWrite(verbatim);
    String content = artifactRequestBodyAsJson(requestBody, CedarResourceType.INSTANCE, templateResolverFor(c));
    Response artifactResponse = executeResourceCreateOrUpdateViaPut(c, CedarResourceType.INSTANCE, tiid, folderId, content, verbatim);
    return negotiateArtifactResponse(artifactResponse, CedarResourceType.INSTANCE);
  }

  @DELETE
  @Timed
  @Path("/{template_instance_id}")
  @Operation(summary = "Delete a template instance", description = "Conditionally delete a template instance using its current ETag. "
      + "CEDAR durably records an accepted deletion before removing content and automatically resumes any interrupted "
      + "workspace-graph, search-index, or value-recommender cleanup.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @ApiResponses({
      @ApiResponse(responseCode = "202", description = "Content deleted; durable downstream cleanup is pending"),
      @ApiResponse(responseCode = "204", description = "Deletion completed across content and downstream stores"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response deleteTemplateInstance(
      @Parameter(description = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_DELETE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return executeArtifactDelete(c, CedarResourceType.INSTANCE, tiid);
  }

  @GET
  @Timed
  @Path("/{template_instance_id}/permissions")
  @Operation(summary = "Get permissions of a template instance", description = "Get permissions of a template instance.", tags = {"Template Instances", "Permissions"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateInstancePermissions(
      @Parameter(description = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateResourcePermissionsResponse(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{template_instance_id}/permissions")
  @Operation(summary = "Update permissions of a template instance", description = "Update permissions of a template instance.", tags = {"Template Instances", "Permissions"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response updateTemplateInstancePermissions(
      @Parameter(description = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return updateResourcePermissions(c, tiid);
  }

  @GET
  @Timed
  @Path("/{template_instance_id}/report")
  @Operation(summary = "Get report of a template instance", description = "Get report of a template instance.", tags = {"Template Instances", "Resource Report"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateInstanceReport(
      @Parameter(description = "Template Instance identifier.", required = true) @PathParam(PP_TEMPLATE_INSTANCE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateArtifactReportResponse(c, tiid);
  }

}
