package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
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

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/command", tags = "Command", authorizations = {@Authorization("api_key")})
public class CommandCategoriesResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandCategoriesResource.class);

  public CommandCategoriesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/attach-category")
  @ApiOperation(value = "Attach category to an artifact",
      notes = "Attach an existing category to an existing artifact. The user must have 'write' access to the "
          + "artifact. The user must have 'attach' access to the category.",
      tags = {"Command", "Categories", "Category Operations"})
  @ApiImplicitParams({
      @ApiImplicitParam(name = "categoryAttachRequest", value = "Parameters of the attach operation", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachRequest",
          paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response attachCategoryToArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter artifactIdParam = c.request().getRequestBody().get("artifactId");
    CedarParameter categoryIdParam = c.request().getRequestBody().get("categoryId");

    c.must(artifactIdParam).be(NonEmpty);
    c.must(categoryIdParam).be(NonEmpty);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    String artifactId = artifactIdParam.stringValue();
    String categoryId = categoryIdParam.stringValue();

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(artifactId);

    CedarCategoryId ccid = CedarCategoryId.build(categoryId);

    userMustHaveWriteAccessToArtifact(c, aid);

    userMustHaveAttachAccessToCategory(c, ccid);

    FolderServerArtifactCurrentUserReport folderServerResource = getArtifactReport(c, aid);

    boolean attached = categorySession.attachCategoryToArtifact(ccid, aid);
    if (attached) {
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
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
  @ApiOperation(value = "Detach category from an artifact",
      notes = "Detach an existing category from an existing artifact. The user must have 'write' access to the "
          + "artifact. The user must have 'attach' access to the category.",
      tags = {"Command", "Categories", "Category Operations"})
  @ApiImplicitParams({
      @ApiImplicitParam(name = "categoryAttachRequest", value = "Parameters of the detach operation", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachRequest",
          paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response detachCategoryFromArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter artifactIdParam = c.request().getRequestBody().get("artifactId");
    CedarParameter categoryIdParam = c.request().getRequestBody().get("categoryId");

    c.must(artifactIdParam).be(NonEmpty);
    c.must(categoryIdParam).be(NonEmpty);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    String artifactId = artifactIdParam.stringValue();
    String categoryId = categoryIdParam.stringValue();

    CedarUntypedArtifactId aid = CedarUntypedArtifactId.build(artifactId);

    CedarCategoryId ccid = CedarCategoryId.build(categoryId);

    userMustHaveWriteAccessToArtifact(c, aid);

    userMustHaveAttachAccessToCategory(c, ccid);

    FolderServerArtifactCurrentUserReport folderServerResource = getArtifactReport(c, aid);

    boolean attached = categorySession.detachCategoryFromArtifact(ccid, aid);
    if (attached) {
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
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
  @ApiOperation(value = "Attach multiple categories to an artifact",
      notes = "Attach a list of existing categories to an existing artifact. The user must have 'write' access to "
          + "the artifact. The user must have 'attach' access to all the categories. The call will exit at the "
          + "firts category without 'attach' access",
      tags = {"Command", "Categories", "Category Operations"})
  @ApiImplicitParams({
      @ApiImplicitParam(name = "categoryAttachListRequest", value = "Parameters of the attach operation",
          required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.CategoryAttachListRequest",
          paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
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

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

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
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
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
