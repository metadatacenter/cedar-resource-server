package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.LinkedData;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.CreateOrUpdate;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.*;

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
public class TemplatesResource extends AbstractResourceServerResource {

  public TemplatesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplate(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_CREATE);

    String folderIdS;

    CedarParameter folderIdP = c.request().wrapQueryParam(QP_FOLDER_ID, folderId);
    if (folderIdP.isEmpty()) {
      folderIdS = c.getCedarUser().getHomeFolderId();
    } else {
      folderIdS = folderIdP.stringValue();
    }

    FolderServerFolder folder = userMustHaveWriteAccessToFolder(c, folderIdS);
    return executeResourcePostByProxy(c, CedarNodeType.TEMPLATE, folder);
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    //TODO: maybe the returned resource is the same as we read here
    // Check this
    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetByProxy(CedarNodeType.TEMPLATE, id, c);
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetDetailsByProxy(CedarNodeType.TEMPLATE, id, c);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplate(@PathParam(PP_ID) String id, @QueryParam(QP_FOLDER_ID) Optional<String> folderId)
      throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);

    CedarRequestBody requestBody = c.request().getRequestBody();
    c.must(requestBody).be(NonEmpty);

    CedarParameter idInBody = requestBody.get(LinkedData.ID);
    c.must(idInBody).be(NonNull);

    if (!idInBody.stringValue().equals(id)) {
      return CedarResponse.badRequest()
          .errorMessage("The id in the URI and the @id in the body must be equal")
          .parameter(PP_ID, id)
          .parameter(LinkedData.ID, idInBody)
          .build();
    }

    JsonNode templateOnTemplateServer = null;//MicroserviceRequest.templateServer().get(CedarNodeType.TEMPLATE, id);
    JsonNode templateOnWorkspaceServer = null;//MicroserviceRequest.workspaceServer().get(CedarNodeType.TEMPLATE, id);

    if (templateOnTemplateServer == null ^ templateOnWorkspaceServer == null) {
      return CedarResponse.internalServerError()
          .errorMessage("The state of this template is inconsistent on the backend storage! It can not be updated")
          .parameter("presentOnTemplateServer", templateOnTemplateServer != null)
          .parameter("presentOnWorkspaceServer", templateOnWorkspaceServer != null)
          .build();
    }

    CreateOrUpdate createOrUpdate = null;

    if (templateOnTemplateServer == null && templateOnWorkspaceServer == null) {
      createOrUpdate = CreateOrUpdate.CREATE;
    } else {
      createOrUpdate = CreateOrUpdate.UPDATE;
    }

    CedarParameter folderIdP = c.request().wrapQueryParam(QP_FOLDER_ID, folderId);

    if (createOrUpdate == CreateOrUpdate.UPDATE) {
      if (!folderIdP.isEmpty()) {
        return CedarResponse.badRequest()
            .errorMessage("You are not allowed to specify the folder_id if you are trying to update a template")
            .parameter(QP_FOLDER_ID, folderIdP.stringValue())
            .build();
      } else {
        FolderServerResource folderServerResource = userMustHaveWriteAccessToResource(c, id);
        return executeResourcePutByProxy(c, CedarNodeType.TEMPLATE, id, folderServerResource);
      }
    } else if (createOrUpdate == CreateOrUpdate.CREATE) {
      String folderIdS;
      if (folderIdP.isEmpty()) {
        folderIdS = c.getCedarUser().getHomeFolderId();
      } else {
        folderIdS = folderIdP.stringValue();
      }
      FolderServerFolder folder = userMustHaveWriteAccessToFolder(c, folderIdS);
      return executeResourcePutByProxy(c, CedarNodeType.TEMPLATE, id, folder, null);
    }
    return null;
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_DELETE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourceDeleteByProxy(c, CedarNodeType.TEMPLATE, id);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplatePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourcePermissionGetByProxy(id, c);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplatePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourcePermissionPutByProxy(id, c);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceReportGetByProxy(id, c);
  }

}