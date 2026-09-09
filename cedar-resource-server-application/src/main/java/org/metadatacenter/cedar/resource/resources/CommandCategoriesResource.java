package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.LinkedData;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.id.CedarUserId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.rest.assertion.noun.CedarInPlaceParameter;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryPermissionServiceSession;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.RevisionConflictException;
import org.metadatacenter.server.VersionedCategoryPermissions;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarResourceBatchAttachCategoryRequest;
import org.metadatacenter.server.security.model.permission.category.CategoryCapability;
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.RevisionPreconditionParser;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandCategoriesResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandCategoriesResource.class);

  public CommandCategoriesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/attach-category")
  @Operation(summary = "Attach category to an artifact", description = "Attach an existing category to an existing artifact. The user must have the updateResource capability on the "
          + "artifact and the attachCategory capability on the category.", tags = {"Command", "Categories", "Category Operations"})
  @RequestBody(description = "Parameters of the attach operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The artifact's details, as they stood before the category was attached",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactDetails"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response attachCategoryToArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter artifactIdParam = c.request().getRequestBody().get("artifactId");
    CedarParameter categoryIdParam = c.request().getRequestBody().get("categoryId");

    c.must(artifactIdParam).be(NonEmpty);
    c.must(categoryIdParam).be(NonEmpty);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    String artifactId = artifactIdParam.stringValue();
    String categoryId = categoryIdParam.stringValue();

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(artifactId);

    CedarCategoryId ccid = CedarCategoryId.build(categoryId);

    userMustHaveCapabilityOnArtifact(c, aid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.UPDATE_RESOURCE);

    userMustHaveCategoryCapability(c, ccid, CategoryCapability.ATTACH_CATEGORY);

    FolderServerArtifactCurrentUserReport folderServerResource = getArtifactReport(c, aid);

    boolean attached = categorySession.attachCategoryToArtifact(ccid, aid);
    if (attached) {
      FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
      FolderServerArtifact updatedResource = folderSession.findArtifactById(aid);
      updateIndexResource(updatedResource, c, true);
      return Response.ok().entity(folderServerResource).build();
    } else {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.UNABLE_TO_ATTACH_CATEGORY)
          .errorMessage("The category was not attached to the artifact")
          .parameter("categoryId", categoryId)
          .parameter("artifactId", artifactId)
          .build();
    }
  }

  @POST
  @Timed
  @Path("/detach-category")
  @Operation(summary = "Detach category from an artifact", description = "Detach an existing category from an existing artifact. The user must have the updateResource capability on the "
          + "artifact and the detachCategory capability on the category.", tags = {"Command", "Categories", "Category Operations"})
  @RequestBody(description = "Parameters of the detach operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The artifact's details, as they stood before the category was detached",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactDetails"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response detachCategoryFromArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter artifactIdParam = c.request().getRequestBody().get("artifactId");
    CedarParameter categoryIdParam = c.request().getRequestBody().get("categoryId");

    c.must(artifactIdParam).be(NonEmpty);
    c.must(categoryIdParam).be(NonEmpty);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    String artifactId = artifactIdParam.stringValue();
    String categoryId = categoryIdParam.stringValue();

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(artifactId);

    CedarCategoryId ccid = CedarCategoryId.build(categoryId);

    userMustHaveCapabilityOnArtifact(c, aid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.UPDATE_RESOURCE);

    userMustHaveCategoryCapability(c, ccid, CategoryCapability.DETACH_CATEGORY);

    FolderServerArtifactCurrentUserReport folderServerResource = getArtifactReport(c, aid);

    boolean attached = categorySession.detachCategoryFromArtifact(ccid, aid);
    if (attached) {
      FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
      FolderServerArtifact updatedResource = folderSession.findArtifactById(aid);
      updateIndexResource(updatedResource, c, true);
      return Response.ok().entity(folderServerResource).build();
    } else {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.UNABLE_TO_DETACH_CATEGORY)
          .errorMessage("The category was not detached from the artifact")
          .parameter("categoryId", categoryId)
          .parameter("artifactId", artifactId)
          .build();
    }
  }

  @POST
  @Timed
  @Path("/attach-categories")
  @Operation(summary = "Attach multiple categories to an artifact", description = "Attach a list of existing categories to an existing artifact. The user must have the updateResource capability on "
          + "the artifact and the attachCategory capability on every category. Authorization is completed before any category is attached.",
      tags = {"Command", "Categories", "Category Operations"})
  @RequestBody(description = "Parameters of the attach operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachListRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The artifact's details, as they stood before the categories were attached",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactDetails"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response attachCategoriesToArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    c.must(c.request().getRequestBody()).be(NonEmpty);
    JsonNode categoryAttachmentRequest = c.request().getRequestBody().asJson();

    CedarResourceBatchAttachCategoryRequest categoryRequest = null;
    try {
      categoryRequest = JsonMapper.STRICT_MAPPER.treeToValue(categoryAttachmentRequest, CedarResourceBatchAttachCategoryRequest.class);
    } catch (JsonProcessingException e) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.MALFORMED_JSON_REQUEST_BODY)
          .errorMessage("Malformed batch category attachment request")
          .exception(e)
          .build();
    }

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    String artifactId = categoryRequest.getArtifactId();
    CedarParameter artifactIdParam = new CedarInPlaceParameter("artifactId", artifactId);
    c.must(artifactIdParam).be(NonEmpty);

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(artifactId);

    userMustHaveCapabilityOnArtifact(c, aid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.UPDATE_RESOURCE);

    FolderServerArtifactCurrentUserReport folderServerResource = getArtifactReport(c, aid);

    // Validate the complete batch before changing the graph. Otherwise a category without attach
    // permission late in the list leaves every earlier category attached despite the 403 response.
    List<CedarCategoryId> categoryIds = new ArrayList<>();
    for (String categoryId : categoryRequest.getCategoryIds()) {
      CedarParameter categoryIdParam = new CedarInPlaceParameter("categoryId", categoryId);
      c.must(categoryIdParam).be(NonEmpty);
      CedarCategoryId ccid = CedarCategoryId.build(categoryId);
      userMustHaveCategoryCapability(c, ccid, CategoryCapability.ATTACH_CATEGORY);
      categoryIds.add(ccid);
    }

    boolean changed = categorySession.attachCategoriesToArtifact(categoryIds, aid);
    if (changed) {
      FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
      FolderServerArtifact updatedResource = folderSession.findArtifactById(aid);
      updateIndexResource(updatedResource, c, true);
      return Response.ok().entity(folderServerResource).build();
    } else {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.NO_CATEGORIES_WERE_ATTACHED)
          .errorMessage("No categories were attached")
          .parameter("categoryIds", categoryRequest.getCategoryIds())
          .parameter("artifactId", artifactId)
          .build();
    }
  }

  @POST
  @Timed
  @Path("/transfer-category-ownership")
  @Operation(summary = "Transfer category ownership",
      description = "Replace the owner of a category with another user. Only the current owner may perform this operation. "
          + "Send the ETag returned by the category permissions endpoint in If-Match.",
      tags = {"Command", "Categories", "Permissions"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "The category and new owner.", required = true,
      content = @Content(schema = @Schema(
          implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.TransferOwnershipRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Ownership transferred",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryPermissions")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired")
  })
  public Response transferCategoryOwnership() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    CedarParameter idParam = c.request().getRequestBody().get(LinkedData.ID);
    CedarParameter newOwnerIdParam = c.request().getRequestBody().get("newOwnerId");
    c.must(idParam).be(NonEmpty);
    c.must(newOwnerIdParam).be(NonEmpty);

    CedarCategoryId categoryId = CedarCategoryId.build(idParam.stringValue());
    userMustHaveCategoryCapability(c, categoryId, CategoryCapability.TRANSFER_OWNERSHIP);

    String ifMatch = c.getIfMatchHeader();
    if (ifMatch == null || ifMatch.isBlank()) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_REQUIRED)
          .id(categoryId)
          .errorMessage("Transferring category ownership requires the permissions ETag in If-Match")
          .build();
    }

    CategoryPermissionServiceSession permissions = dataServices.getCategoryPermissionServiceSession(c);
    BackendCallResult<VersionedCategoryPermissions> result;
    try {
      result = permissions.transferCategoryOwnership(categoryId,
          CedarUserId.build(newOwnerIdParam.stringValue()), RevisionPreconditionParser.parse(ifMatch));
    } catch (RevisionConflictException e) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
          .id(categoryId)
          .errorMessage("The category permissions have changed since they were read")
          .parameter("currentETag", RevisionPreconditionParser.format(e.getCurrentRevision()))
          .build();
    }
    if (result.isError()) {
      throw new CedarBackendException(result);
    }

    VersionedCategoryPermissions transferred = result.getPayload();
    return Response.ok(transferred.content())
        .header(HttpHeaders.ETAG, RevisionPreconditionParser.format(transferred.revision()))
        .build();
  }
}
