package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.resources.swaggermodel.Category;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarBadRequestException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.extract.FolderServerCategoryExtractWithChildren;
import org.metadatacenter.model.request.CategoryListRequest;
import org.metadatacenter.model.response.FolderServerCategoryListResponse;
import org.metadatacenter.operation.CedarOperations;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryPermissionServiceSession;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.permission.category.CategoryPermissionRequest;
import org.metadatacenter.server.security.model.permission.category.CategoryPermissions;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedQuery;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_CATEGORY_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_LIMIT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_OFFSET;
import static org.metadatacenter.id.CedarCategoryId.CATEGORY_ID_ROOT;
import static org.metadatacenter.rest.assertion.GenericAssertions.*;

@Path("/categories")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/categories", tags = "Categories", authorizations = {@Authorization("api_key")})
public class CategoriesResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CategoriesResource.class);

  public CategoriesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @ApiOperation(value = "Get all categories", notes = "Get the list of all categories.", response = Category.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A category", response = Category.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getAllCategories(
      @ApiParam(value = "Paging limit")
      @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
      @ApiParam(value = "Paging offset")
      @QueryParam(QP_OFFSET) Optional<Integer> offsetParam) throws CedarException {

    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_READ);

    PagedQuery pagedQuery = new PagedQuery(cedarConfig.getCategoryRESTAPI().getPagination())
        .limit(limitParam)
        .offset(offsetParam);
    pagedQuery.validate();

    int limit = pagedQuery.getLimit();
    int offset = pagedQuery.getOffset();

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    List<FolderServerCategory> categories = categorySession.getAllCategories(limit, offset);
    long total = categorySession.getCategoryCount();

    FolderServerCategoryListResponse r = new FolderServerCategoryListResponse();

    CategoryListRequest req = new CategoryListRequest();
    req.setLimit(limit);
    req.setOffset(offset);

    r.setRequest(req);

    r.setCategories(categories);

    r.setTotalCount(total);
    r.setCurrentOffset(offset);

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI absoluteURI = builder
        .build();

    r.setPaging(LinkHeaderUtil.getPagingLinkHeaders(absoluteURI.toString(), total, limit, offset));

    ProvenanceNameUtil.addProvenanceDisplayNames(r);

    return Response.ok().entity(r).build();
  }


  @POST
  @Timed
  @ApiOperation(value = "Create a category", notes = "Create a category.", code = 201, response = Category.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "category", value = "The category to be created", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.Category", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 201, message = "A category", response = Category.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response createCategory() throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_CREATE);

    CedarRequestBody requestBody = c.request().getRequestBody();

    CedarParameter categoryName = requestBody.get(NodeProperty.NAME.getValue());
    CedarParameter categoryDescription = requestBody.get(NodeProperty.DESCRIPTION.getValue());
    CedarParameter parentCategoryId = requestBody.get(NodeProperty.PARENT_CATEGORY_ID.getValue());
    CedarParameter identifier = requestBody.get(NodeProperty.IDENTIFIER.getValue());
    c.should(categoryName, categoryDescription, parentCategoryId).be(NonNull).otherwiseBadRequest();
    CedarCategoryId ccParentId = CedarCategoryId.build(parentCategoryId.stringValue());

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategory parentCategory = categorySession.getCategoryById(ccParentId);
    c.should(parentCategory).be(NonNull).otherwiseBadRequest(
        new CedarErrorPack()
            .message("The parent category can not be found!")
            .parameter(NodeProperty.PARENT_CATEGORY_ID.getValue(), ccParentId)
            .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.ID.getValue(), ccParentId.getId()))
            .errorKey(CedarErrorKey.PARENT_CATEGORY_NOT_FOUND)
    );

    userMustHaveWriteAccessToCategory(c, ccParentId);

    FolderServerCategory newCategory = null;
    // If the category already exists, return it
    FolderServerCategory existingCategory = categorySession.getCategoryByParentAndName(ccParentId, categoryName.stringValue());
    if (existingCategory != null) {
      log.warn("There is a category with the same name (" + categoryName.stringValue()
          + ") under the parent category. Category names must be unique!");
      newCategory = existingCategory;
    }
    else {
      newCategory = categorySession.createCategory(ccParentId, categoryName.stringValue(), categoryDescription.stringValue(),
          identifier.stringValue());
      c.should(newCategory).be(NonNull).otherwiseInternalServerError(
          new CedarErrorPack()
              .message("There was an error while creating the category!")
              .operation(CedarOperations.create(FolderServerCategory.class, NodeProperty.NAME.getValue(), categoryName))
      );
      ProvenanceNameUtil.addProvenanceDisplayName(newCategory);
    }

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI uri = builder.path(CedarUrlUtil.urlEncode(newCategory.getId())).build();
    return Response.created(uri).entity(newCategory).build();
  }

  @GET
  @Timed
  @Path("/root")
  @ApiOperation(value = "Get root category", notes = "Get root category.", response = Category.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A category", response = Category.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findRootCategory() throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_READ);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategory category = categorySession.getRootCategory();
    c.should(category).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The root category can not be found!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", CATEGORY_ID_ROOT))
    );
    ProvenanceNameUtil.addProvenanceDisplayName(category);
    return Response.ok().entity(category).build();
  }

  @GET
  @Timed
  @Path("/{category_id}")
  @ApiOperation(value = "Get a category", notes = "Get a category.", response = Category.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A category", response = Category.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findCategory(
      @ApiParam(value = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_READ);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    CedarCategoryId ccid = CedarCategoryId.build(id);
    FolderServerCategory category = categorySession.getCategoryById(ccid);
    c.should(category).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The category can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", ccid.getId()))
    );

    ProvenanceNameUtil.addProvenanceDisplayName(category);
    return Response.ok().entity(category).build();
  }

  @GET
  @Timed
  @Path("/tree")
  @ApiOperation(value = "Get category tree", notes = "Get category tree.", response = Category.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A category", response = Category.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findCategoryTree(
      @ApiParam(value = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_READ);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategoryExtractWithChildren category = categorySession.getCategoryTree();

    return Response.ok().entity(category).build();
  }

  @PUT
  @Timed
  @Path("/{category_id}")
  @ApiOperation(value = "Update a category", notes = "Update a category.", response = Category.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "category", value = "The category to be updated", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.Category", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "A category", response = Category.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateCategory(
      @ApiParam(value = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_UPDATE);
    CedarCategoryId ccid = CedarCategoryId.build(id);

    CedarRequestBody requestBody = c.request().getRequestBody();

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategory existingCategory = categorySession.getCategoryById(ccid);
    c.should(existingCategory).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The category can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", ccid.getId()))
    );

    CedarParameter categoryName = requestBody.get(NodeProperty.NAME.getValue());
    CedarParameter categoryDescription = requestBody.get(NodeProperty.DESCRIPTION.getValue());
    CedarParameter categoryIdentifier = requestBody.get(NodeProperty.IDENTIFIER.getValue());
    c.should(categoryName, categoryDescription).be(NonNull).otherwiseBadRequest();

    FolderServerCategory sameNameCategory =
        categorySession.getCategoryByParentAndName(CedarCategoryId.build(existingCategory.getParentCategoryId()),
            categoryName.stringValue());

    if (sameNameCategory != null && !sameNameCategory.getId().equals(ccid.getId())) {
      log.warn("There is a category with the same name (" + sameNameCategory.getName()
          + ") under the parent category. Category names must be unique!");
    }

    FolderServerCategory categoryWritable = userMustHaveWriteAccessToCategory(c, ccid);

    Map<NodeProperty, String> updateFields = new HashMap<>();
    updateFields.put(NodeProperty.NAME, categoryName.stringValue());
    updateFields.put(NodeProperty.NAME_LOWER, categoryName.stringValue().toLowerCase());
    updateFields.put(NodeProperty.DESCRIPTION, categoryDescription.stringValue());
    updateFields.put(NodeProperty.IDENTIFIER, categoryIdentifier.stringValue());
    FolderServerCategory updatedCategory = categorySession.updateCategoryById(ccid, updateFields);

    c.should(updatedCategory).be(NonNull).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while updating the category!")
            .operation(CedarOperations.update(FolderServerCategory.class, "id", ccid.getId()))
    );

    ProvenanceNameUtil.addProvenanceDisplayName(updatedCategory);

    return Response.ok().entity(updatedCategory).build();
  }

  @DELETE
  @Timed
  @Path("/{category_id}")
  @ApiOperation(value = "Delete a category", notes = "Delete a category.")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Successful operation (no content)"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response deleteCategory(
      @ApiParam(value = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_DELETE);
    CedarCategoryId ccid = CedarCategoryId.build(id);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    FolderServerCategory existingCategory = categorySession.getCategoryById(ccid);

    c.should(existingCategory).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The category can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", ccid.getId()))
    );

    //TODO: check if it can be deleted:
    // - it has no child nodes
    // - it has no artifacts attached
    // - also perform some kind of permission checking

    FolderServerCategory categoryWritable = userMustHaveWriteAccessToCategory(c, ccid);

    if (categoryWritable.getParentCategoryId() == null) {
      CedarErrorPack cedarErrorPack = new CedarErrorPack();
      cedarErrorPack.status(CedarResponseStatus.BAD_REQUEST)
          .message("The root category can not be deleted!")
          .parameter(NodeProperty.ID.getValue(), ccid.getId())
          .operation(CedarOperations.delete(FolderServerCategory.class, NodeProperty.ID.getValue(), ccid.getId()))
          .errorKey(CedarErrorKey.ROOT_CATEGORY_CAN_NOT_BE_DELETED);
      throw new CedarBadRequestException(cedarErrorPack);
    }

    boolean deleted = categorySession.deleteCategoryById(ccid);
    c.should(deleted).be(True).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while deleting the category!")
            .operation(CedarOperations.delete(FolderServerCategory.class, "id", ccid.getId()))
    );

    //searchPermissionEnqueueService.groupDeleted(id);

    // TODO: if there will be a search index for this, handle that as well. Throughout the whole process.

    return Response.noContent().build();
  }

  @GET
  @Timed
  @Path("/{category_id}/permissions")
  @ApiOperation(value = "Get permissions of a category", notes = "Get permissions of a category.",
      tags = {"Categories", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getCategoryPermissions(
      @ApiParam(value = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_READ);

    CategoryPermissionServiceSession categoryPermissionSession =
        CedarDataServices.getCategoryPermissionServiceSession(c);

    CedarCategoryId categoryId = CedarCategoryId.build(id);
    userMustHaveWriteAccessToCategory(c, categoryId);

    CategoryPermissions permissions = categoryPermissionSession.getCategoryPermissions(categoryId);
    return Response.ok().entity(permissions).build();

  }

  @PUT
  @Timed
  @Path("/{category_id}/permissions")
  @ApiOperation(value = "Update permissions of a category", notes = "Update permissions of a category.",
      tags = {"Categories", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateCategoryPermissions(
      @ApiParam(value = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_UPDATE);

    c.must(c.request().getRequestBody()).be(NonEmpty);
    JsonNode permissionUpdateRequest = c.request().getRequestBody().asJson();

    CedarCategoryId categoryId = CedarCategoryId.build(id);
    userMustHaveWriteAccessToCategory(c, categoryId);

    CategoryPermissionServiceSession categoryPermissionSession =
        CedarDataServices.getCategoryPermissionServiceSession(c);

    CategoryPermissionRequest permissionsRequest = null;
    try {
      permissionsRequest = JsonMapper.MAPPER.treeToValue(permissionUpdateRequest, CategoryPermissionRequest.class);
    } catch (JsonProcessingException e) {
      log.error("Error while reading permission update request", e);
      return CedarResponse.badRequest()
          .errorMessage("Error while reading permission update request!")
          .errorKey(CedarErrorKey.MALFORMED_JSON_REQUEST_BODY)
          .exception(e)
          .build();
    }

    BackendCallResult backendCallResult = categoryPermissionSession.updateCategoryPermissions(categoryId,
        permissionsRequest);
    if (backendCallResult.isError()) {
      throw new CedarBackendException(backendCallResult);
    }

    CategoryPermissions permissions = categoryPermissionSession.getCategoryPermissions(categoryId);
    return Response.ok().entity(permissions).build();
  }


}
