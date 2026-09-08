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
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.error.CedarErrorReasonKey;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarBadRequestException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerCategoryCurrentUserReport;
import org.metadatacenter.model.folderserver.extract.FolderServerCategoryExtractWithChildren;
import org.metadatacenter.model.request.CategoryListRequest;
import org.metadatacenter.model.response.FolderServerCategoryListResponse;
import org.metadatacenter.operation.CedarOperations;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryPermissionServiceSession;
import org.metadatacenter.server.CategoryNotEmptyException;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.RevisionConflictException;
import org.metadatacenter.server.RevisionPrecondition;
import org.metadatacenter.server.SiblingNameConflictException;
import org.metadatacenter.server.VersionedCategoryPermissions;
import org.metadatacenter.server.VersionedResource;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.permission.category.CategoryPermissionRequest;
import org.metadatacenter.server.security.model.permission.category.CategoryCapability;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedQuery;
import org.metadatacenter.util.http.RevisionPreconditionParser;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
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
@Tag(name = "Categories")
@SecurityRequirement(name = "api_key")
public class CategoriesResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CategoriesResource.class);

  public CategoriesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Operation(summary = "Get all categories", description = "Get the list of all categories.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "One page of categories",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryListResponse"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getAllCategories(
      @Parameter(description = "Paging limit")
      @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
      @Parameter(description = "Paging offset")
      @QueryParam(QP_OFFSET) Optional<Integer> offsetParam) throws CedarException {

    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);

    PagedQuery pagedQuery = new PagedQuery(cedarConfig.getCategoryRESTAPI().getPagination())
        .limit(limitParam)
        .offset(offsetParam);
    pagedQuery.validate();

    int limit = pagedQuery.getLimit();
    int offset = pagedQuery.getOffset();

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);
    FolderServerCategory root = categorySession.getRootCategory();
    c.should(root).be(NonNull).otherwiseNotFound(
        new CedarErrorPack().message("The root category can not be found!")
            .errorKey(CedarErrorKey.CATEGORY_NOT_FOUND));
    userMustHaveCategoryCapability(c, root.getResourceId(), CategoryCapability.READ_CATEGORY);

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
  @Operation(summary = "Create a category", description = "Create a category.")
  @RequestBody(description = "The category to be created", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.Category.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "A category and what the current user may do with it",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryDetails")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "A sibling category with the same name already exists"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response createCategory() throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();

    CedarParameter categoryName = requestBody.get(NodeProperty.NAME.getValue());
    CedarParameter categoryDescription = requestBody.get(NodeProperty.DESCRIPTION.getValue());
    CedarParameter parentCategoryId = requestBody.get(NodeProperty.PARENT_CATEGORY_ID.getValue());
    CedarParameter identifier = requestBody.get(NodeProperty.IDENTIFIER.getValue());
    c.should(categoryName, categoryDescription, parentCategoryId).be(NonNull).otherwiseBadRequest();
    CedarCategoryId ccParentId = CedarCategoryId.build(parentCategoryId.stringValue());

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    FolderServerCategory parentCategory = categorySession.getCategoryById(ccParentId);
    c.should(parentCategory).be(NonNull).otherwiseBadRequest(
        new CedarErrorPack()
            .message("The parent category can not be found!")
            .parameter(NodeProperty.PARENT_CATEGORY_ID.getValue(), ccParentId)
            .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.ID.getValue(), ccParentId.getId()))
            .errorKey(CedarErrorKey.PARENT_CATEGORY_NOT_FOUND)
    );

    userMustHaveCategoryCapability(c, ccParentId, CategoryCapability.CREATE_CHILD_CATEGORY);

    FolderServerCategory newCategory;
    FolderServerCategory existingCategory = categorySession.getCategoryByParentAndName(ccParentId, categoryName.stringValue());
    if (existingCategory != null) {
      log.warn("There is a category with the same name (" + categoryName.stringValue()
          + ") under the parent category. Category names must be unique!");
      return siblingNameConflictResponse(categoryName.stringValue());
    } else {
      try {
        newCategory = categorySession.createCategory(ccParentId, categoryName.stringValue(), categoryDescription.stringValue(),
            identifier.stringValue());
      } catch (SiblingNameConflictException e) {
        return siblingNameConflictResponse(categoryName.stringValue());
      }
      c.should(newCategory).be(NonNull).otherwiseInternalServerError(
          new CedarErrorPack()
              .message("There was an error while creating the category!")
              .operation(CedarOperations.create(FolderServerCategory.class, NodeProperty.NAME.getValue(), categoryName))
      );
    }

    FolderServerCategoryCurrentUserReport newCategoryReport = userMustHaveCategoryCapability(
        c, newCategory.getResourceId(), CategoryCapability.READ_CATEGORY);
    ProvenanceNameUtil.addProvenanceDisplayName(newCategoryReport);

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI uri = builder.path(CedarUrlUtil.urlEncode(newCategory.getId())).build();
    return Response.created(uri).header(HttpHeaders.ETAG, RevisionPreconditionParser.format(1L))
        .entity(newCategoryReport).build();
  }

  @GET
  @Timed
  @Path("/root")
  @Operation(summary = "Get root category", description = "Get root category.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The root category and what the current user may do with it",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryDetails"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response findRootCategory() throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    FolderServerCategory category = categorySession.getRootCategory();
    c.should(category).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The root category can not be found!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", CATEGORY_ID_ROOT))
    );
    FolderServerCategoryCurrentUserReport report = userMustHaveCategoryCapability(
        c, category.getResourceId(), CategoryCapability.READ_CATEGORY);
    ProvenanceNameUtil.addProvenanceDisplayName(report);
    return Response.ok().entity(report).build();
  }

  @GET
  @Timed
  @Path("/{category_id}")
  @Operation(summary = "Get a category", description = "Get a category.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A category and what the current user may do with it",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryDetails")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response findCategory(
      @Parameter(description = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    CedarCategoryId ccid = CedarCategoryId.build(id);

    VersionedResource<FolderServerCategory> snapshot = categorySession.getVersionedCategoryById(ccid);
    c.should(snapshot).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The category can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", ccid.getId()))
    );

    FolderServerCategoryCurrentUserReport report = userMustHaveCategoryCapability(
        c, ccid, CategoryCapability.READ_CATEGORY);
    ProvenanceNameUtil.addProvenanceDisplayName(report);
    return Response.ok().header(HttpHeaders.ETAG, RevisionPreconditionParser.format(snapshot.revision()))
        .entity(report).build();
  }

  @GET
  @Timed
  @Path("/tree")
  @Operation(summary = "Get category tree", description = "Get category tree.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The root category, with every category beneath it",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryTree"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response findCategoryTree(
      @Parameter(description = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    FolderServerCategory root = categorySession.getRootCategory();
    c.should(root).be(NonNull).otherwiseNotFound(
        new CedarErrorPack().message("The root category can not be found!")
            .errorKey(CedarErrorKey.CATEGORY_NOT_FOUND));
    userMustHaveCategoryCapability(c, root.getResourceId(), CategoryCapability.READ_CATEGORY);

    FolderServerCategoryExtractWithChildren category = categorySession.getCategoryTree();

    return Response.ok().entity(category).build();
  }

  @PUT
  @Timed
  @Path("/{category_id}")
  @Operation(summary = "Update a category", description = "Update a category.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "The category to be updated", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.Category.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The updated category and what the current user may do with it",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryDetails")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "A sibling category already has the requested name"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response updateCategory(
      @Parameter(description = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    CedarCategoryId ccid = CedarCategoryId.build(id);

    CedarRequestBody requestBody = c.request().getRequestBody();

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);

    FolderServerCategory existingCategory = categorySession.getCategoryById(ccid);
    if (existingCategory == null) {
      return categoryUpdateTargetDeleted();
    }

    userMustHaveCategoryCapability(c, ccid, CategoryCapability.UPDATE_CATEGORY);

    String ifMatch = c.getIfMatchHeader();
    if (ifMatch == null || ifMatch.isBlank()) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_REQUIRED)
          .errorMessage("Updating a category requires the ETag returned by GET in If-Match")
          .build();
    }
    RevisionPrecondition precondition = RevisionPreconditionParser.parse(ifMatch);

    CedarParameter categoryName = requestBody.get(NodeProperty.NAME.getValue());
    CedarParameter categoryDescription = requestBody.get(NodeProperty.DESCRIPTION.getValue());
    CedarParameter categoryIdentifier = requestBody.get(NodeProperty.IDENTIFIER.getValue());
    c.should(categoryName, categoryDescription).be(NonNull).otherwiseBadRequest();

    FolderServerCategory sameNameCategory =
        categorySession.getCategoryByParentAndName(CedarCategoryId.build(existingCategory.getParentCategoryId()),
            categoryName.stringValue());

    if (sameNameCategory != null && !sameNameCategory.getId().equals(ccid.getId())) {
      return CedarResponse.conflict()
          .errorKey(CedarErrorKey.CATEGORY_ALREADY_PRESENT)
          .errorMessage("There is already a category with the same name under the parent category")
          .parameter("name", categoryName.stringValue())
          .parameter("conflictingCategoryId", sameNameCategory.getId())
          .build();
    }

    Map<NodeProperty, String> updateFields = new HashMap<>();
    updateFields.put(NodeProperty.NAME, categoryName.stringValue());
    updateFields.put(NodeProperty.NAME_LOWER, categoryName.stringValue().toLowerCase());
    updateFields.put(NodeProperty.DESCRIPTION, categoryDescription.stringValue());
    updateFields.put(NodeProperty.IDENTIFIER, categoryIdentifier.stringValue());
    VersionedResource<FolderServerCategory> updatedCategory;
    try {
      updatedCategory = categorySession.updateCategoryById(ccid, updateFields, precondition);
    } catch (SiblingNameConflictException e) {
      return siblingNameConflictResponse(categoryName.stringValue());
    } catch (RevisionConflictException e) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
          .parameter("currentETag", RevisionPreconditionParser.format(e.getCurrentRevision()))
          .errorMessage("The category has been updated since it was read")
          .build();
    }

    if (updatedCategory == null) {
      return categoryUpdateTargetDeleted();
    }

    FolderServerCategoryCurrentUserReport updatedCategoryReport = userMustHaveCategoryCapability(
        c, ccid, CategoryCapability.UPDATE_CATEGORY);
    ProvenanceNameUtil.addProvenanceDisplayName(updatedCategoryReport);

    return Response.ok().header(HttpHeaders.ETAG, RevisionPreconditionParser.format(updatedCategory.revision()))
        .entity(updatedCategoryReport).build();
  }

  private static Response categoryUpdateTargetDeleted() {
    return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
        .errorMessage("The category no longer exists, so the conditional update can not be applied")
        .build();
  }

  @DELETE
  @Timed
  @Path("/{category_id}")
  @Operation(summary = "Delete a category", description = "Delete a category.",
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Successful operation (no content)"),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Category still has children or attached artifacts"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response deleteCategory(
      @Parameter(description = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    CedarCategoryId ccid = CedarCategoryId.build(id);

    CategoryServiceSession categorySession = dataServices.getCategoryServiceSession(c);
    FolderServerCategory existingCategory = categorySession.getCategoryById(ccid);

    c.should(existingCategory).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The category can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", ccid.getId()))
    );

    FolderServerCategory categoryWritable = userMustHaveCategoryCapability(
        c, ccid, CategoryCapability.DELETE_CATEGORY);

    if (categoryWritable.getParentCategoryId() == null) {
      CedarErrorPack cedarErrorPack = new CedarErrorPack();
      cedarErrorPack.status(CedarResponseStatus.BAD_REQUEST)
          .message("The root category can not be deleted!")
          .parameter(NodeProperty.ID.getValue(), ccid.getId())
          .operation(CedarOperations.delete(FolderServerCategory.class, NodeProperty.ID.getValue(), ccid.getId()))
          .errorKey(CedarErrorKey.ROOT_CATEGORY_CAN_NOT_BE_DELETED);
      throw new CedarBadRequestException(cedarErrorPack);
    }

    String ifMatch = c.getIfMatchHeader();
    if (ifMatch == null || ifMatch.isBlank()) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_REQUIRED)
          .errorMessage("Deleting a category requires the ETag returned by GET in If-Match")
          .build();
    }
    boolean deleted;
    try {
      deleted = categorySession.deleteCategoryById(ccid, RevisionPreconditionParser.parse(ifMatch));
    } catch (CategoryNotEmptyException e) {
      return CedarResponse.conflict()
          .id(id)
          .errorKey(CedarErrorKey.CATEGORY_CAN_NOT_BE_DELETED)
          .errorReasonKey(CedarErrorReasonKey.NON_EMPTY_CATEGORY)
          .parameter("childCategoryCount", e.getChildCategoryCount())
          .parameter("artifactCount", e.getArtifactCount())
          .errorMessage("Categories with children or attached artifacts can not be deleted")
          .build();
    } catch (RevisionConflictException e) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
          .parameter("currentETag", RevisionPreconditionParser.format(e.getCurrentRevision()))
          .errorMessage("The category has been updated since it was read")
          .build();
    }
    if (!deleted) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
          .errorMessage("The category was deleted before this deletion could be applied")
          .build();
    }

    //searchPermissionEnqueueService.groupDeleted(id);

    // TODO: if there will be a search index for this, handle that as well. Throughout the whole process.

    return Response.noContent().build();
  }

  @GET
  @Timed
  @Path("/{category_id}/permissions")
  @Operation(summary = "Get category permissions",
      description = "Get the category owner, direct user grants and direct group grants. Any user with the readCategory capability may read this response.",
      tags = {"Categories", "Permissions"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Category permissions",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryPermissions")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response getCategoryPermissions(
      @Parameter(description = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CategoryPermissionServiceSession categoryPermissionSession =
        dataServices.getCategoryPermissionServiceSession(c);

    CedarCategoryId categoryId = CedarCategoryId.build(id);
    userMustHaveCategoryCapability(c, categoryId, CategoryCapability.READ_CATEGORY);

    VersionedCategoryPermissions permissions =
        categoryPermissionSession.getVersionedCategoryPermissions(categoryId);
    return Response.ok()
        .header(HttpHeaders.ETAG, RevisionPreconditionParser.format(permissions.revision()))
        .entity(permissions.content()).build();

  }

  @PUT
  @Timed
  @Path("/{category_id}/permissions")
  @Operation(summary = "Replace category grants",
      description = "Atomically replace the direct user and group role grants. This operation does not change ownership or inherited roles. The caller must have the manageGrants capability.",
      tags = {"Categories", "Permissions"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Complete replacement for the category's direct grants.", required = true,
      content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryAclUpdateRequest")))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Updated category permissions",
          content = @Content(schema = @Schema(ref = "#/components/schemas/CategoryPermissions")),
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response updateCategoryPermissions(
      @Parameter(description = "Category identifier. Example: https://repo.metadatacenter.org/categories/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_CATEGORY_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    c.must(c.request().getRequestBody()).be(NonEmpty);
    JsonNode permissionUpdateRequest = c.request().getRequestBody().asJson();

    CedarCategoryId categoryId = CedarCategoryId.build(id);
    userMustHaveCategoryCapability(c, categoryId, CategoryCapability.MANAGE_GRANTS);

    CategoryPermissionServiceSession categoryPermissionSession =
        dataServices.getCategoryPermissionServiceSession(c);

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

    String ifMatch = c.getIfMatchHeader();
    if (ifMatch == null || ifMatch.isBlank()) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_REQUIRED)
          .id(categoryId)
          .errorMessage("Replacing category permissions requires the ETag returned by GET in If-Match")
          .build();
    }

    BackendCallResult<VersionedCategoryPermissions> backendCallResult;
    try {
      backendCallResult = categoryPermissionSession.updateCategoryPermissions(categoryId,
          permissionsRequest, RevisionPreconditionParser.parse(ifMatch));
    } catch (RevisionConflictException e) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
          .id(categoryId)
          .errorMessage("The category permissions have been updated since they were read")
          .parameter("currentETag", RevisionPreconditionParser.format(e.getCurrentRevision()))
          .build();
    }
    if (backendCallResult.isError()) {
      throw new CedarBackendException(backendCallResult);
    }

    VersionedCategoryPermissions permissions = backendCallResult.getPayload();
    return Response.ok()
        .header(HttpHeaders.ETAG, RevisionPreconditionParser.format(permissions.revision()))
        .entity(permissions.content()).build();
  }


}
