package org.metadatacenter.cedar.resource.resources;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.PATCH;
import com.codahale.metrics.annotation.Timed;
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
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.util.artifact.SchemaArtifactDocument;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarFieldId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.artifact.ArtifactYamlTranscoder;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.QP_COMPACT;
import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_VERBATIM;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-fields")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Template Fields")
@SecurityRequirement(name = "api_key")
public class TemplateFieldsResource extends AbstractResourceServerResource {

  public TemplateFieldsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Consumes({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Create a template field", description = "Create a template field. The body can be JSON or YAML, "
      + "selected via the Content-Type header. A YAML body must be the full or minimal form: the "
      + "compact form is a lossy read-time convenience and is rejected.")
  @RequestBody(description = "The template field to be created", required = true, content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "A template field", content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response createTemplateField(
      @Parameter(description = "Folder identifier. The artifact will be created in this folder. The user must have the "
          + "createInFolder capability on the folder. If not provided, the artifact will be created in the user's home folder.")
      @QueryParam(QP_FOLDER_ID) Optional<String> folderId,
      @Parameter(description = "Not supported on write operations; write responses always render the full form.")
      @QueryParam(QP_COMPACT) Optional<Boolean> compactParam,
      @Parameter(hidden = true) String requestBody) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_CREATE);
    rejectCompactOnWriteOperations(compactParam);
    String content = artifactRequestBodyAsJson(requestBody, CedarResourceType.FIELD);
    Response artifactResponse = executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.FIELD, Optional.empty(), folderId, content);
    return negotiateArtifactResponse(artifactResponse, CedarResourceType.FIELD);
  }

  @GET
  @Timed
  @Path("/{id}")
  @Parameter(name = "format", in = io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY,
      description = "Output representation; overrides Accept. Unknown or repeated values return 400. "
          + "RDF N-Quads is supported for instances only; schema artifacts return 406.",
      schema = @Schema(type = "string", allowableValues = {"jsonld", "json", "yaml", "rdf-nquad"}))
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Get a template field", description = "Get a template field as JSON or YAML, selected via the "
      + "Accept header.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A template field", content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "406", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "The requested representation is not supported for this artifact"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response findTemplateField(
      @Parameter(description = "Template Field identifier. Example: https://repo.metadatacenter.org/template-fields/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_ID) String id,
      @Parameter(description = "When requesting YAML, produce a compact representation.")
      @QueryParam(QP_COMPACT) Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    userMustHaveCapabilityOnArtifact(c, fid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);
    return executeArtifactGetNegotiated(c, CedarResourceType.FIELD, fid, compactParam);
  }


  @GET
  @Timed
  @Path("/{id}/download")
  @Parameter(name = "format", in = io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY,
      description = "Output representation; overrides Accept. Unknown or repeated values return 400. "
          + "RDF N-Quads is supported for instances only; schema artifacts return 406.",
      schema = @Schema(type = "string", allowableValues = {"jsonld", "json", "yaml", "rdf-nquad"}))
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Download a template field", description = "Download a template field as JSON or YAML, selected via the Accept "
      + "header.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template field content as an attachment",
          content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)),
          headers = @Header(name = "Content-Disposition", description = "The response is an attachment named after the template field's identifier.",
              schema = @Schema(type = "string"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "406", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "The requested representation is not supported for this artifact"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getTemplateFieldDownload(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id,
      @Parameter(description = "Desired output format: 'application/json' or 'application/yaml'.")
      @HeaderParam("Accept") String acceptHeader,
      @Parameter(description = "When downloading YAML, produce a compact representation.")
      @QueryParam(QP_COMPACT) Optional<Boolean> compactParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    userMustHaveCapabilityOnArtifact(c, fid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

    return downloadArtifact(c, CedarResourceType.FIELD, fid, compactParam);
  }

  @GET
  @Timed
  @Path("/{id}/details")
  @Operation(summary = "Get details of a template field", description = "Get details of a template field.", tags = {"Template Fields", "Resource Details"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template field's details",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactDetails")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response findTemplateFieldDetails(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return getDetails(c, fid);
  }

  @PUT
  @Timed
  @Path("/{id}")
  @Produces({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Consumes({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  @Operation(summary = "Update a template field", description = "Update a template field. The body can be JSON or YAML, "
      + "selected via the Content-Type header. A YAML body must be the full or minimal form: the "
      + "compact form is a lossy read-time convenience and is rejected.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatchForCreateOrReplace"))
  @RequestBody(description = "The template field to be updated", required = true, content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A template field",
          content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(ref = "#/components/schemas/ArtifactRecord")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "201", description = "A template field created with the supplied identifier",
          content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response updateTemplateField(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id,
      @Parameter(description = "Folder identifier.") @QueryParam(QP_FOLDER_ID) Optional<String> folderId,
      @Parameter(description = "Not supported on write operations; write responses always render the full form.")
      @QueryParam(QP_COMPACT) Optional<Boolean> compactParam,
      @Parameter(description = "Admin only. Replace the artifact with exactly the document supplied: no provenance stamped, no child identifier minted. The artifact must exist and the body must be JSON. A published artifact may be replaced this way, since a verbatim write states the whole document rather than editing it.")
      @QueryParam(QP_VERBATIM) Optional<Boolean> verbatimParam,
      @Parameter(hidden = true) String requestBody) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    CedarFieldId fid = CedarFieldId.build(id);

    rejectCompactOnWriteOperations(compactParam);
    boolean verbatim = verbatimParam != null && verbatimParam.isPresent() && verbatimParam.get();
    if (verbatim) {
      c.must(c.user()).have(CedarPermission.WRITE_ARTIFACT_VERBATIM);
    }
    rejectYamlVerbatimWrite(verbatim);
    String content = artifactRequestBodyAsJson(requestBody, CedarResourceType.FIELD);
    Response artifactResponse = executeResourceCreateOrUpdateViaPut(c, CedarResourceType.FIELD, fid, folderId, content, verbatim);
    return negotiateArtifactResponse(artifactResponse, CedarResourceType.FIELD);
  }

  @PATCH
  @Timed
  @Path("/{id}")
  @Consumes({jakarta.ws.rs.core.MediaType.APPLICATION_JSON, org.metadatacenter.constant.HttpConstants.CONTENT_TYPE_APPLICATION_MERGE_PATCH_JSON})
  @Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  @Operation(summary = "Change the name, description or open state of a template field", description = "Set schema:name and/or schema:description, or expose the template field through OpenView, or "
      + "withdraw it, with {\"isOpen\": true} or {\"isOpen\": false}. Use the ETag returned by GET /template-fields/{id}/details.",
      tags = {"Template Fields", "OpenView"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "The new name and/or description, or the OpenView state", required = true,
      content = @Content(schema = @Schema(ref = "#/components/schemas/ResourceStatePatch")))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template field, with its new OpenView state",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactRecord")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "The body is not {\"isOpen\": true} or {\"isOpen\": false}"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response patchTemplateField(
      @Parameter(description = "Template field identifier.", required = true) @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    if (c.request().getRequestBody().asJson() == null
        || !c.request().getRequestBody().asJson().has("isOpen")) {
      return renameResource(CedarResourceType.FIELD, id, c);
    }
    return patchArtifactOpenState(CedarResourceType.FIELD, id, c);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  @Operation(summary = "Delete a template field", description = "Conditionally delete a template field using its current ETag. "
      + "CEDAR durably records an accepted deletion before removing content and automatically resumes any interrupted "
      + "workspace-graph or search-index cleanup.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @ApiResponses({
      @ApiResponse(responseCode = "202", description = "The deletion is recorded and will be completed without the "
              + "caller. Either the artifact server did not confirm it, by an outage or no answer, and it is sent "
              + "again until it is answered, or the content is deleted and the workspace-graph or search-index "
              + "cleanup is still pending. The body says which.",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactDeletionAccepted"))),
      @ApiResponse(responseCode = "204", description = "Deletion completed across content and downstream stores"),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response deleteTemplateField(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_DELETE);
    CedarFieldId fid = CedarFieldId.build(id);

    return executeArtifactDelete(c, CedarResourceType.FIELD, fid);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  @Operation(summary = "Get permissions of a template field", description = "Get permissions of a template field.", tags = {"Template Fields", "Permissions"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template field's permissions",
          content = @Content(schema = @Schema(implementation = CedarNodePermissionsWithExtract.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getTemplateFieldPermissions(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateResourcePermissionsResponse(c, fid);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  @Operation(summary = "Update permissions of a template field", description = "Update permissions of a template field.", tags = {"Template Fields", "Permissions"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Complete replacement for the template field's direct grants.", required = true,
      content = @Content(schema = @Schema(ref = "#/components/schemas/ResourceAclUpdateRequest")))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template field's updated permissions",
          content = @Content(schema = @Schema(implementation = CedarNodePermissionsWithExtract.class)),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response updateTemplateFieldPermissions(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);
    CedarFieldId fid = CedarFieldId.build(id);

    return updateResourcePermissions(c, fid);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  @Operation(summary = "Get report of a template field", description = "Get report of a template field.", tags = {"Template Fields", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template field's report",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactReport"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getTemplateFieldInstanceReport(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateArtifactReportResponse(c, fid);
  }

  @GET
  @Timed
  @Path("/{id}/versions")
  @Operation(summary = "Get a list of versions of a template field", description = "Get a list of versions of a template field.", tags = {"Template Fields", "Resource Report", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The template field's versions, newest first",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ResourceListResponse"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getTemplateFieldVersions(
      @Parameter(description = "Template Field identifier.", required = true) @PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(org.metadatacenter.model.CedarResourceType.FIELD, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    CedarFieldId fid = CedarFieldId.build(id);

    return generateNodeVersionsResponse(c, fid);
  }

  @PATCH
  @Timed
  @Path("/{id}/permissions")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Transfer ownership", description = "Change the owner using the permissions ETag in If-Match.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.TransferOwnershipRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Updated permissions",
          content = @Content(schema = @Schema(implementation = CedarNodePermissionsWithExtract.class)),
          headers = @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Invalid owner or request body", content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "404", description = "Resource not found", content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content(schema = @Schema(implementation = CedarError.class)))
  })
  public Response transferOwnership(@PathParam(PP_ID) String id) throws CedarException {
    return transferResourceOwnership(CedarResourceType.FIELD, id);
  }

  @jakarta.ws.rs.PATCH
  @Timed
  @Path("/{id}/publication")
  @Operation(summary = "Publish artifact.", description = "Publish artifact. The 'bibo:status' of the artifact will be changed from 'bibo:draft' to "
          + "'bibo:published'. The 'pav:version' will be also set.", tags = {"Validation", "Command", "Versioning"})
  @RequestBody(description = "Info about the publishing process", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.PublishArtifactRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The published artifact",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactRecord"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "Only the artifact's owner may publish it, whatever role another user holds "
              + "(versioningOnlyByOwner). A caller who cannot read the artifact, or lacks the permission to "
              + "update artifacts of its type, is also refused."),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response publishArtifact(@PathParam(PP_ID) String id) throws CedarException {
    return operationResource(new CommandVersionResource(cedarConfig)).publishArtifact("template-fields", id);
  }
  @PATCH
  @Timed
  @Path("/{id}/annotations")
  @Operation(summary = "Set the DOI of an artifact", description = "Set the DOI annotation of an artifact. The user must have the updateResource capability on the artifact. The "
          + "resource type must support DOIs, and an existing DOI can not be altered.")
  @RequestBody(description = "The artifact and the DOI to record", required = true,
      content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.SetDoiRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The artifact, carrying its DOI",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactRecord"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response setDOI(@PathParam("id") String id) throws CedarException {
    return operationResource(new CommandAnnotationsResource(cedarConfig)).setDOI("template-fields", id);
  }
}
