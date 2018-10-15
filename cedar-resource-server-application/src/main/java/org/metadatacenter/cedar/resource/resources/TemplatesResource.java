package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerResource;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.ValidTemplate;

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
public class TemplatesResource extends AbstractResourceServerResource {

  private static final boolean ENABLE_VALIDATION = false;

  public TemplatesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplate(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_CREATE);
    if (ENABLE_VALIDATION) {
      c.must(c.request()).be(ValidTemplate);
    }

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
    if (ENABLE_VALIDATION) {
      c.must(c.request()).be(ValidTemplate);
    }

    FolderServerResource folderServerResource = userMustHaveWriteAccessToResource(c, id);
    return executeResourcePutByProxy(c, CedarNodeType.TEMPLATE, id, folderServerResource);
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

  @GET
  @Timed
  @Path("/{id}/versions")
  public Response getTemplateVersions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceVersionsGetByProxy(c, id);
  }

}