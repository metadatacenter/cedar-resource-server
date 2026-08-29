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
import org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateElement;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarElementId;
import org.metadatacenter.model.CedarResourceType;
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

import static org.metadatacenter.constant.CedarPathParameters.PP_TEMPLATE_ELEMENT_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_VERBATIM;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-elements")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Template Elements")
@SecurityRequirement(name = "api_key")
public class TemplateElementsResource extends AbstractResourceServerResource {

  public TemplateElementsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Consumes({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Create a template element", description = "Create a template element. The body can be JSON or "
      + "YAML, selected via the Content-Type header. A YAML body must be the full or minimal form: "
      + "the compact form is a lossy read-time convenience and is rejected.")
  @RequestBody(description = "The template element to be created", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateElement.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "A template element", content = @Content(schema = @Schema(implementation = TemplateElement.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response createTemplateElement(
      @Parameter(description = "Folder identifier. The artifact will be created in this folder. The user must have write "
          + "access to the folder. If not provided, the artifact will be created in the user's home folder.")
      @QueryParam(QP_FOLDER_ID) Optional<String> folderId,
      @Parameter(description = "Not supported on write operations; write responses always render the full form.")
      @QueryParam("compact") Optional<Boolean> compactParam,
      @Parameter(hidden = true) String requestBody) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_CREATE);
    rejectCompactOnWriteOperations(compactParam);
    String content = artifactRequestBodyAsJson(requestBody, CedarResourceType.ELEMENT);
    Response artifactResponse = executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.ELEMENT, Optional.empty(), folderId, content);
    return negotiateArtifactResponse(artifactResponse, CedarResourceType.ELEMENT);
  }

  @GET
  @Timed
  @Path("/{template_element_id}")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Get a template element", description = "Get a template element as JSON or YAML, selected via the "
      + "Accept header.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A template element", content = @Content(schema = @Schema(implementation = TemplateElement.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findTemplateElement(
      @Parameter(description = "Template Element identifier. Example: https://repo.metadatacenter.org/template-elements/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_TEMPLATE_ELEMENT_ID) String id,
      @Parameter(description = "When requesting YAML, produce a compact representation.")
      @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    userMustHaveReadAccessToArtifact(c, eid);
    return executeArtifactGetNegotiated(c, CedarResourceType.ELEMENT, eid, compactParam);
  }

  @POST
  @Timed
  @Path("/{template_element_id}/download")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Download a template element", description = "Download a template element as JSON or YAML, selected "
      + "via the Accept header.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template element content as an attachment"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response downloadTemplateElement(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id,
      @Parameter(description = "Desired output format: 'application/json' or 'application/yaml'.")
      @HeaderParam("Accept") String acceptHeader,
      @Parameter(description = "When downloading YAML, produce a compact representation.")
      @QueryParam("compact") Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    userMustHaveReadAccessToArtifact(c, eid);

    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.ELEMENT, eid);
    ClassicHttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    // If error while retrieving artifact, re-run and return proxy call directly
    if (proxyResponse.getCode() != Response.Status.OK.getStatusCode()) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.ELEMENT, id, c);
    }
    HttpEntity entity = proxyResponse.getEntity();
    JsonNode elementNode = null;

    try {
      String elementSource = EntityUtils.toString(entity, CharEncoding.UTF_8);
      elementNode = JsonMapper.MAPPER.readTree(elementSource);
    } catch (IOException | ParseException e) {
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
    if (acceptHeader.contains("yaml")) {  // matches both application/yaml and application/x-yaml
      String fileName = elementUUID + ".yaml";
      String content = ArtifactYamlTranscoder.jsonToYaml(elementNode, CedarResourceType.ELEMENT, compactParam.isPresent() && compactParam.get());
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
  @Operation(summary = "Get details of a template element", description = "Get details of a template element.", tags = {"Template Elements", "Resource Details"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findTemplateElementDetails(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return getDetails(c, eid);
  }

  @PUT
  @Timed
  @Path("/{template_element_id}")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Consumes({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Update a template element", description = "Update a template element. The body can be JSON or "
      + "YAML, selected via the Content-Type header. A YAML body must be the full or minimal form: "
      + "the compact form is a lossy read-time convenience and is rejected.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatchForCreateOrReplace"))
  @RequestBody(description = "The template element to be updated", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.TemplateElement.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A template element", content = @Content(schema = @Schema(implementation = TemplateElement.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "201", description = "A template element created with the supplied identifier",
          content = @Content(schema = @Schema(implementation = TemplateElement.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response updateTemplateElement(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id,
      @Parameter(description = "Folder identifier.") @QueryParam(QP_FOLDER_ID) Optional<String> folderId,
      @Parameter(description = "Not supported on write operations; write responses always render the full form.")
      @QueryParam("compact") Optional<Boolean> compactParam,
      @Parameter(description = "Admin only. Replace the artifact with exactly the document supplied: no provenance stamped, no child identifier minted. The artifact must exist and the body must be JSON.")
      @QueryParam(QP_VERBATIM) Optional<Boolean> verbatimParam,
      @Parameter(hidden = true) String requestBody) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    CedarElementId eid = CedarElementId.build(id);

    rejectCompactOnWriteOperations(compactParam);
    boolean verbatim = verbatimParam != null && verbatimParam.isPresent() && verbatimParam.get();
    if (verbatim) {
      c.must(c.user()).have(CedarPermission.WRITE_ARTIFACT_VERBATIM);
    }
    rejectYamlVerbatimWrite(verbatim);
    String content = artifactRequestBodyAsJson(requestBody, CedarResourceType.ELEMENT);
    Response artifactResponse = executeResourceCreateOrUpdateViaPut(c, CedarResourceType.ELEMENT, eid, folderId, content, verbatim);
    return negotiateArtifactResponse(artifactResponse, CedarResourceType.ELEMENT);
  }

  @DELETE
  @Timed
  @Path("/{template_element_id}")
  @Operation(summary = "Delete a template element", description = "Delete a template element.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Successful operation (no content)"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response deleteTemplateElement(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_DELETE);
    CedarElementId eid = CedarElementId.build(id);

    return executeArtifactDelete(c, CedarResourceType.ELEMENT, eid);
  }

  @GET
  @Timed
  @Path("/{template_element_id}/permissions")
  @Operation(summary = "Get permissions of a template element", description = "Get permissions of a template element.", tags = {"Template Elements", "Permissions"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateElementPermissions(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return generateResourcePermissionsResponse(c, eid);
  }

  @PUT
  @Timed
  @Path("/{template_element_id}/permissions")
  @Operation(summary = "Update permissions of a template element", description = "Update permissions of a template element.", tags = {"Template Elements", "Permissions"},
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
  public Response updateTemplateElementPermissions(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_UPDATE);
    CedarElementId eid = CedarElementId.build(id);

    return updateResourcePermissions(c, eid);
  }

  @GET
  @Timed
  @Path("/{template_element_id}/report")
  @Operation(summary = "Get report of a template element", description = "Get report of a template element.", tags = {"Template Elements", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateElementInstanceReport(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return generateArtifactReportResponse(c, eid);
  }

  @GET
  @Timed
  @Path("/{template_element_id}/versions")
  @Operation(summary = "Get a list of versions of a template element", description = "Get a list of versions of a template element.", tags = {"Template Elements", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getTemplateElementVersions(
      @Parameter(description = "Template Element identifier.", required = true) @PathParam(PP_TEMPLATE_ELEMENT_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);
    CedarElementId eid = CedarElementId.build(id);

    return generateNodeVersionsResponse(c, eid);
  }

}
