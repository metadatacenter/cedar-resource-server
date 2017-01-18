package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerFolder;
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
import static org.metadatacenter.constant.CedarQueryParameters.QP_IMPORT_MODE;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/templates", description = "Template operations")
public class TemplatesResource extends AbstractResourceServerResource {

  public TemplatesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @ApiOperation(
      value = "Create template")
  @POST
  @Timed
  public Response createTemplate(@QueryParam(QP_FOLDER_ID) Optional<String> folderId, @QueryParam(QP_IMPORT_MODE)
      Optional<Boolean> importMode) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_CREATE);

    CedarParameter folderIdP = c.request().wrapQueryParam(QP_FOLDER_ID, folderId);
    c.must(folderIdP).be(NonEmpty);

    String folderIdS = folderIdP.stringValue();

    FolderServerFolder folder = userMustHaveWriteAccessToFolder(c, folderIdS);
    return executeResourcePostByProxy(c, CedarNodeType.TEMPLATE, folder, importMode);
  }

  @ApiOperation(
      value = "Find template by id")
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

  @ApiOperation(
      value = "Find template details by id")
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

  @ApiOperation(
      value = "Update template")
  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourcePutByProxy(CedarNodeType.TEMPLATE, id, c);
  }

  @ApiOperation(
      value = "Delete template")
  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_DELETE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourceDeleteByProxy(CedarNodeType.TEMPLATE, id, c);
  }

  @ApiOperation(
      value = "Get permissions of a template")
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

  @ApiOperation(
      value = "Update template permissions")
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
}