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

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/template-elements")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/template-elements", description = "Template element operations")
public class TemplateElementsResource extends AbstractResourceServerResource {

  public TemplateElementsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @ApiOperation(
      value = "Create template element")
  @POST
  @Timed
  public Response createTemplateElement(@QueryParam("folderId") Optional<String> folderId, @QueryParam("importMode")
      Optional<Boolean> importMode) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_CREATE);

    CedarParameter folderIdP = c.request().wrapQueryParam("folderId", folderId);
    c.must(folderIdP).be(NonEmpty);

    String folderIdS = folderIdP.stringValue();

    FolderServerFolder folder = userMustHaveWriteAccessToFolder(c, folderIdS);
    return executeResourcePostByProxy(c, CedarNodeType.ELEMENT, folder, importMode);
  }

  @ApiOperation(
      value = "Find template element by id")
  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateElement(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetByProxy(CedarNodeType.ELEMENT, id, c);
  }

  @ApiOperation(
      value = "Find template element details by id")
  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateElementDetails(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetDetailsByProxy(CedarNodeType.ELEMENT, id, c);
  }

  @ApiOperation(
      value = "Update template element")
  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateElement(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_UPDATE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourcePutByProxy(CedarNodeType.ELEMENT, id, c);
  }

  @ApiOperation(
      value = "Delete template element")
  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateElement(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_DELETE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourceDeleteByProxy(CedarNodeType.ELEMENT, id, c);
  }

  @ApiOperation(
      value = "Get permissions of a template element")
  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateElementPermissions(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourcePermissionGetByProxy(id, c);
  }

  @ApiOperation(
      value = "Update template element permissions")
  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateElementPermissions(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_UPDATE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourcePermissionPutByProxy(id, c);
  }

}