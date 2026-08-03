package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.rest.assertion.noun.CedarInPlaceParameter;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.security.model.auth.CedarResourceBatchAttachCategoryRequest;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
  @Operation(summary = "Attach category to an artifact", description = "Attach an existing category to an existing artifact. The user must have 'write' access to the "
          + "artifact. The user must have 'attach' access to the category.", tags = {"Command", "Categories", "Category Operations"})
  @RequestBody(description = "Parameters of the attach operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
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

    userMustHaveWriteAccessToArtifact(c, aid);

    userMustHaveAttachAccessToCategory(c, ccid);

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
  @Operation(summary = "Detach category from an artifact", description = "Detach an existing category from an existing artifact. The user must have 'write' access to the "
          + "artifact. The user must have 'attach' access to the category.", tags = {"Command", "Categories", "Category Operations"})
  @RequestBody(description = "Parameters of the detach operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
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

    userMustHaveWriteAccessToArtifact(c, aid);

    userMustHaveAttachAccessToCategory(c, ccid);

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
  @Operation(summary = "Attach multiple categories to an artifact", description = "Attach a list of existing categories to an existing artifact. The user must have 'write' access to "
          + "the artifact. The user must have 'attach' access to all the categories. The call will exit at the "
          + "firts category without 'attach' access", tags = {"Command", "Categories", "Category Operations"})
  @RequestBody(description = "Parameters of the attach operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachListRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response attachCategoriesToArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    c.must(c.request().getRequestBody()).be(NonEmpty);
    JsonNode categoryAttachmentRequest = c.request().getRequestBody().asJson();

    CedarResourceBatchAttachCategoryRequest categoryRequest = null;
    try {
      categoryRequest = JsonMapper.MAPPER.treeToValue(categoryAttachmentRequest, CedarResourceBatchAttachCategoryRequest.class);
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

    userMustHaveWriteAccessToArtifact(c, aid);

    FolderServerArtifactCurrentUserReport folderServerResource = getArtifactReport(c, aid);

    boolean changed = false;
    for (String categoryId : categoryRequest.getCategoryIds()) {
      CedarCategoryId ccid = CedarCategoryId.build(categoryId);
      userMustHaveAttachAccessToCategory(c, ccid);
      boolean attached = categorySession.attachCategoryToArtifact(ccid, aid);
      if (attached) {
        changed = true;
      }
    }
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
}
