package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.exception.CedarBadRequestException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.extract.FolderServerCategoryExtractWithChildren;
import org.metadatacenter.model.request.CategoryListRequest;
import org.metadatacenter.model.response.FolderServerCategoryListResponse;
import org.metadatacenter.operation.CedarOperations;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedQuery;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_LIMIT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_OFFSET;
import static org.metadatacenter.id.CedarCategoryId.CATEGORY_ID_ROOT;
import static org.metadatacenter.rest.assertion.GenericAssertions.*;

@Path("/categories")
@Produces(MediaType.APPLICATION_JSON)
public class CategoriesResource extends AbstractResourceServerResource {

  public CategoriesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  public Response getAllCategories(@QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                                   @QueryParam(QP_OFFSET) Optional<Integer> offsetParam) throws CedarException {

    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_READ);

    PagedQuery pagedQuery = new PagedQuery(cedarConfig.getCategoryRESTAPI().getPagination())
        .limit(limitParam)
        .offset(offsetParam);
    pagedQuery.validate();

    Integer limit = pagedQuery.getLimit();
    Integer offset = pagedQuery.getOffset();

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

    addProvenanceDisplayNames(r);

    return Response.ok().entity(r).build();
  }


  @POST
  @Timed
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
            .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.ID.getValue(),
                ccParentId.getId()))
            .errorKey(CedarErrorKey.PARENT_CATEGORY_NOT_FOUND)
    );

    FolderServerCategory oldCategory = categorySession.getCategoryByParentAndName(ccParentId,
        categoryName.stringValue());
    c.should(oldCategory).be(Null).otherwiseBadRequest(
        new CedarErrorPack()
            .message("There is a category with the same name under the parent category. Category names must be unique!")
            .parameter(NodeProperty.NAME.getValue(), categoryName.stringValue())
            .parameter(NodeProperty.PARENT_CATEGORY_ID.getValue(), parentCategoryId.stringValue())
            .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.NAME.getValue(), categoryName))
            .errorKey(CedarErrorKey.CATEGORY_ALREADY_PRESENT)
    );

    FolderServerCategory parentCategoryWritable = userMustHaveWriteAccessToCategory(c, ccParentId);

    FolderServerCategory newCategory = categorySession.createCategory(ccParentId,
        categoryName.stringValue(), categoryDescription.stringValue(), identifier.stringValue());
    c.should(newCategory).be(NonNull).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while creating the category!")
            .operation(CedarOperations.create(FolderServerCategory.class, NodeProperty.NAME.getValue(), categoryName))
    );

    addProvenanceDisplayName(newCategory);

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI uri = builder.path(CedarUrlUtil.urlEncode(newCategory.getId())).build();
    return Response.created(uri).entity(newCategory).build();
  }

  @GET
  @Timed
  @Path("/root")
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
    addProvenanceDisplayName(category);
    return Response.ok().entity(category).build();
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findCategory(@PathParam(PP_ID) String id) throws CedarException {
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

    addProvenanceDisplayName(category);
    return Response.ok().entity(category).build();
  }

  @GET
  @Timed
  @Path("/tree")
  public Response findCategoryTree(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.CATEGORY_READ);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategoryExtractWithChildren category = categorySession.getCategoryTree();

    return Response.ok().entity(category).build();
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateCategory(@PathParam(PP_ID) String id) throws CedarException {
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
      CedarErrorPack cedarErrorPack = new CedarErrorPack();
      cedarErrorPack.status(Response.Status.BAD_REQUEST)
          .message("There is a category with the same name under the parent category. Category names must be unique!")
          .parameter(NodeProperty.NAME.getValue(), categoryName.stringValue())
          .parameter(NodeProperty.PARENT_CATEGORY_ID.getValue(), existingCategory.getParentCategoryId())
          .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.NAME.getValue(), categoryName))
          .errorKey(CedarErrorKey.CATEGORY_ALREADY_PRESENT);
      throw new CedarBadRequestException(cedarErrorPack);
    }

    FolderServerCategory categoryWritable = userMustHaveWriteAccessToCategory(c, ccid);

    Map<NodeProperty, String> updateFields = new HashMap<>();
    updateFields.put(NodeProperty.NAME, categoryName.stringValue());
    updateFields.put(NodeProperty.DESCRIPTION, categoryDescription.stringValue());
    updateFields.put(NodeProperty.IDENTIFIER, categoryIdentifier.stringValue());
    FolderServerCategory updatedCategory = categorySession.updateCategoryById(ccid, updateFields);

    c.should(updatedCategory).be(NonNull).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while updating the category!")
            .operation(CedarOperations.update(FolderServerCategory.class, "id", ccid.getId()))
    );

    addProvenanceDisplayName(updatedCategory);

    return Response.ok().entity(updatedCategory).build();
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteCategory(@PathParam(PP_ID) String id) throws CedarException {
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
      cedarErrorPack.status(Response.Status.BAD_REQUEST)
          .message("The root category can not be deleted!")
          .parameter(NodeProperty.ID.getValue(), ccid.getId())
          .operation(CedarOperations.delete(FolderServerCategory.class, NodeProperty.ID.getValue(), ccid.getId()))
          .errorKey(CedarErrorKey.ROOT_CATEGORY_CAN_NOT_BE_DELETED);
      throw new CedarBadRequestException(cedarErrorPack);
    }

      /*
    boolean isAdministrator = groupSession.userAdministersGroup(id) || c.getCedarUser().has
        (UPDATE_NOT_ADMINISTERED_GROUP);
    c.should(isAdministrator).be(True).otherwiseForbidden(
        new CedarErrorPack()
            .errorKey(GROUP_CAN_BY_DELETED_ONLY_BY_GROUP_ADMIN)
            .message("Only the administrators can delete the group!")
            .operation(CedarOperations.delete(FolderServerGroup.class, "id", id))
    );
    */


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


}
