package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarAssertionResult;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarBadRequestException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.model.request.CategoryListRequest;
import org.metadatacenter.model.response.FolderServerCategoryListResponse;
import org.metadatacenter.operation.CedarOperations;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
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
import static org.metadatacenter.error.CedarErrorKey.GROUP_CAN_BY_DELETED_ONLY_BY_GROUP_ADMIN;
import static org.metadatacenter.error.CedarErrorKey.SPECIAL_GROUP_CAN_NOT_BE_DELETED;
import static org.metadatacenter.rest.assertion.GenericAssertions.*;
import static org.metadatacenter.server.security.model.auth.CedarPermission.GROUP_DELETE;
import static org.metadatacenter.server.security.model.auth.CedarPermission.UPDATE_NOT_ADMINISTERED_GROUP;

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
    //c.must(c.user()).have(CATEGORY_READ);

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
    //c.must(c.user()).have(CATEGORY_CREATE);

    CedarRequestBody requestBody = c.request().getRequestBody();

    CedarParameter categoryName = requestBody.get(NodeProperty.NAME.getValue());
    CedarParameter categoryDescription = requestBody.get(NodeProperty.DESCRIPTION.getValue());
    CedarParameter parentCategoryId = requestBody.get(NodeProperty.PARENT_CATEGORY_ID.getValue());
    c.should(categoryName, categoryDescription, parentCategoryId).be(NonNull).otherwiseBadRequest();

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategory parentCategory = categorySession.getCategoryById(parentCategoryId.stringValue());
    c.should(parentCategory).be(NonNull).otherwiseBadRequest(
        new CedarErrorPack()
            .message("The parent category can not be found!")
            .parameter(NodeProperty.PARENT_CATEGORY_ID.getValue(), parentCategoryId.stringValue())
            .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.ID.getValue(), parentCategoryId))
            .errorKey(CedarErrorKey.PARENT_CATEGORY_NOT_FOUND)
    );

    FolderServerCategory oldCategory = categorySession.getCategoryByNameAndParent(categoryName.stringValue(),
        parentCategoryId.stringValue());
    c.should(oldCategory).be(Null).otherwiseBadRequest(
        new CedarErrorPack()
            .message("There is a category with the same name under the parent category. Category names must be unique!")
            .parameter(NodeProperty.NAME.getValue(), categoryName.stringValue())
            .parameter(NodeProperty.PARENT_CATEGORY_ID.getValue(), parentCategoryId.stringValue())
            .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.NAME.getValue(), categoryName))
            .errorKey(CedarErrorKey.CATEGORY_ALREADY_PRESENT)
    );

    FolderServerCategory newCategory = categorySession.createCategory(categoryName.stringValue(),
        categoryDescription.stringValue(),
        parentCategoryId.stringValue());
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
  @Path("/{id}")
  public Response findCategory(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    //c.must(c.user()).have(GROUP_READ);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategory category = categorySession.getCategoryById(id);
    c.should(category).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The category can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", id))
    );

    addProvenanceDisplayName(category);
    return Response.ok().entity(category).build();
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateCategory(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    //c.must(c.user()).have(GROUP_UPDATE);

    CedarRequestBody requestBody = c.request().getRequestBody();

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategory existingCategory = categorySession.getCategoryById(id);
    c.should(existingCategory).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The category can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "id", id))
    );

    CedarParameter categoryName = requestBody.get(NodeProperty.NAME.getValue());
    CedarParameter categoryDescription = requestBody.get(NodeProperty.DESCRIPTION.getValue());
    c.should(categoryName, categoryDescription).be(NonNull).otherwiseBadRequest();

    FolderServerCategory sameNameCategory = categorySession.getCategoryByNameAndParent(categoryName.stringValue(),
        existingCategory.getParentCategoryId());

    if (sameNameCategory != null && !sameNameCategory.getId().equals(id)) {
      CedarErrorPack cedarErrorPack = new CedarErrorPack();
      cedarErrorPack.status(Response.Status.BAD_REQUEST)
          .message("There is a category with the same name under the parent category. Category names must be unique!")
          .parameter(NodeProperty.NAME.getValue(), categoryName.stringValue())
          .parameter(NodeProperty.PARENT_CATEGORY_ID.getValue(), existingCategory.getParentCategoryId())
          .operation(CedarOperations.lookup(FolderServerCategory.class, NodeProperty.NAME.getValue(), categoryName))
          .errorKey(CedarErrorKey.CATEGORY_ALREADY_PRESENT);
      throw new CedarBadRequestException(cedarErrorPack);
    }

    Map<NodeProperty, String> updateFields = new HashMap<>();
    updateFields.put(NodeProperty.NAME, categoryName.stringValue());
    updateFields.put(NodeProperty.DESCRIPTION, categoryDescription.stringValue());
    FolderServerCategory updatedCategory = categorySession.updateCategoryById(id, updateFields);

    c.should(updatedCategory).be(NonNull).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while updating the category!")
            .operation(CedarOperations.update(FolderServerCategory.class, "id", id))
    );

    return Response.ok().entity(updatedCategory).build();
  }

  private static void checkUniqueness(FolderServerGroup otherGroup, FolderServerGroup existingGroup) throws
      CedarException {
    if (otherGroup != null && !otherGroup.getId().equals(existingGroup.getId())) {
      CedarAssertionResult ar = new CedarAssertionResult(
          "There is a group with the new name present in the system. Group names must be unique!")
          .parameter("schema:name", otherGroup.getName())
          .parameter("id", otherGroup.getId())
          .badRequest();
      throw new CedarBackendException(ar);
    }
  }

  private static FolderServerGroup findNonSpecialGroupById(CedarRequestContext c, GroupServiceSession groupSession,
                                                           String id) throws CedarException {
    FolderServerGroup existingGroup = groupSession.findGroupById(id);
    c.should(existingGroup).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The group can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerGroup.class, "id", id))
    );

    if (existingGroup.getSpecialGroup() != null) {
      CedarAssertionResult ar = new CedarAssertionResult("Special groups can not be modified!")
          .parameter("id", id)
          .parameter("specialGroup", existingGroup.getSpecialGroup())
          .badRequest();
      throw new CedarBackendException(ar);
    }
    return existingGroup;
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteCategory(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(GROUP_DELETE);

    GroupServiceSession groupSession = CedarDataServices.getGroupServiceSession(c);
    FolderServerGroup existingGroup = groupSession.findGroupById(id);

    c.should(existingGroup).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The group can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerGroup.class, "id", id))
    );

    String specialGroup = existingGroup.getSpecialGroup();
    c.should(specialGroup).be(Null).otherwiseBadRequest(
        new CedarErrorPack()
            .errorKey(SPECIAL_GROUP_CAN_NOT_BE_DELETED)
            .parameter("schema:name", existingGroup.getName())
            .message("The special group '" + specialGroup + "'can not be deleted!")
            .operation(CedarOperations.delete(FolderServerGroup.class, "id", id))
    );

    boolean isAdministrator = groupSession.userAdministersGroup(id) || c.getCedarUser().has
        (UPDATE_NOT_ADMINISTERED_GROUP);
    c.should(isAdministrator).be(True).otherwiseForbidden(
        new CedarErrorPack()
            .errorKey(GROUP_CAN_BY_DELETED_ONLY_BY_GROUP_ADMIN)
            .message("Only the administrators can delete the group!")
            .operation(CedarOperations.delete(FolderServerGroup.class, "id", id))
    );


    boolean deleted = groupSession.deleteGroupById(id);
    c.should(deleted).be(True).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while deleting the group!")
            .operation(CedarOperations.delete(FolderServerGroup.class, "id", id))
    );

    searchPermissionEnqueueService.groupDeleted(id);

    return Response.noContent().build();
  }


}
