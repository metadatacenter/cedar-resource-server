package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarAssertionResult;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.model.response.FolderServerCategoryListResponse;
import org.metadatacenter.model.response.FolderServerGroupListResponse;
import org.metadatacenter.operation.CedarOperations;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarUrlUtil;
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
import static org.metadatacenter.server.security.model.auth.CedarPermission.*;

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
    c.must(c.user()).have(CATEGORY_READ);

    PagedQuery pagedQuery = new PagedQuery(cedarConfig.getCategoryRESTAPI().getPagination())
        .limit(limitParam)
        .offset(offsetParam);
    pagedQuery.validate();

    Integer limit = pagedQuery.getLimit();
    Integer offset = pagedQuery.getOffset();

    CategoryServiceSession category = CedarDataServices.getCategoryServiceSession(c);
    List<FolderServerCategory> categories = category.getAllCategories(limit, offset);

    FolderServerCategoryListResponse r = new FolderServerCategoryListResponse();
    r.setCategories(categories);

    return Response.ok().entity(r).build();
  }


  @POST
  @Timed
  public Response createCategory() throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CATEGORY_CREATE);

    CedarRequestBody requestBody = c.request().getRequestBody();

    CedarParameter parentCategoryId = requestBody.get("parentCategoryId");
    CedarParameter categoryName = requestBody.get("schema:name");
    CedarParameter categoryDescription = requestBody.get("schema:description");
    c.should(parentCategoryId, categoryName, categoryDescription).be(NonNull).otherwiseBadRequest();

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    FolderServerCategory oldCategory = categorySession.getCategoryByNameAndParent(categoryName.stringValue(), parentCategoryId.stringValue());
    c.should(oldCategory).be(Null).otherwiseBadRequest(
        new CedarErrorPack()
            .message("There is a category with the same name under the parent category. Category names must be unique!")
            .operation(CedarOperations.lookup(FolderServerCategory.class, "schema:name", categoryName))
            .errorKey(CedarErrorKey.CATEGORY_ALREADY_PRESENT)
    );

    FolderServerCategory newCategory = categorySession.createCategory(categoryName.stringValue(), categoryDescription.stringValue(),
        parentCategoryId.stringValue());
    c.should(newCategory).be(NonNull).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while creating the category!")
            .operation(CedarOperations.create(FolderServerCategory.class, "schema:name", categoryName))
    );

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
    c.must(c.user()).have(GROUP_READ);

    GroupServiceSession groupSession = CedarDataServices.getGroupServiceSession(c);

    FolderServerGroup group = groupSession.findGroupById(id);
    c.should(group).be(NonNull).otherwiseNotFound(
        new CedarErrorPack()
            .message("The group can not be found by id!")
            .operation(CedarOperations.lookup(FolderServerGroup.class, "id", id))
    );

    // BackendCallResult<FolderServerGroup> bcr = groupSession.findGroupById(id);
    // c.must(backendCallResult).be(Successful);
    // c.must(backendCallResult).be(Found);
    // FolderServerGroup group = bcr.get();

    return Response.ok().entity(group).build();
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateCategory(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();

    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(GROUP_UPDATE);

    CedarRequestBody requestBody = c.request().getRequestBody();

    GroupServiceSession groupSession = CedarDataServices.getGroupServiceSession(c);
    FolderServerGroup existingGroup = findNonSpecialGroupById(c, groupSession, id);

    CedarParameter groupName = requestBody.get("schema:name");
    CedarParameter groupDescription = requestBody.get("schema:description");
    c.should(groupName, groupDescription).be(NonNull).otherwiseBadRequest();

    // check if the new name is unique
    FolderServerGroup otherGroup = groupSession.findGroupByName(groupName.stringValue());
    checkUniqueness(otherGroup, existingGroup);

    Map<NodeProperty, String> updateFields = new HashMap<>();
    updateFields.put(NodeProperty.NAME, groupName.stringValue());
    updateFields.put(NodeProperty.DESCRIPTION, groupDescription.stringValue());
    FolderServerGroup updatedGroup = groupSession.updateGroupById(id, updateFields);

    c.should(updatedGroup).be(NonNull).otherwiseInternalServerError(
        new CedarErrorPack()
            .message("There was an error while updating the group!")
            .operation(CedarOperations.update(FolderServerGroup.class, "id", id))
    );

    // BackendCallResult<FolderServerGroup> bcr = groupSession.updateGroup(c, groupSession, id, updateFields);
    // c.must(backendCallResult).be(Successful); // InternalServerError, 404 NotFound, 403 Forbidden if special
    // FolderServerGroup existingGroup = bcr.get();

    return Response.ok().entity(updatedGroup).build();
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
