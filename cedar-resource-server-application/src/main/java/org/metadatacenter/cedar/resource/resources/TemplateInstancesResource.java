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

@Path("/template-instances")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/template-instances", description = "Template instance operations")
public class TemplateInstancesResource extends AbstractResourceServerResource {

  public TemplateInstancesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "Create template instance")
  @POST
  @Timed
  public Response createTemplateInstance(@QueryParam("folderId") Optional<String> folderId, @QueryParam("importMode")
      Optional<Boolean> importMode) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE);

    CedarParameter folderIdP = c.request().wrapQueryParam("folderId", folderId);
    c.must(folderIdP).be(NonEmpty);

    String folderIdS = folderIdP.stringValue();

    FolderServerFolder folder = userMustHaveWriteAccessToFolder(c, folderIdS);
    return executeResourcePostByProxy(c, CedarNodeType.INSTANCE, folder, importMode);
  }

  @ApiOperation(
      value = "Find template instance by id")
  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateInstance(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetByProxy(CedarNodeType.INSTANCE, id, c);
  }

  @ApiOperation(
      value = "Find template instance details by id")
  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateInstanceDetails(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetDetailsByProxy(CedarNodeType.INSTANCE, id, c);
  }

  @ApiOperation(
      value = "Update template instance")
  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateInstance(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourcePutByProxy(CedarNodeType.INSTANCE, id, c);
  }

  @ApiOperation(
      value = "Delete template instance")
  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateInstance(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_DELETE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourceDeleteByProxy(CedarNodeType.INSTANCE, id, c);
  }

  @ApiOperation(
      value = "Get permissions of a template instance")
  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateInstancePermissions(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourcePermissionGetByProxy(id, c);
  }

  @ApiOperation(
      value = "Update template instance permissions")
  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateInstancePermissions(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourcePermissionPutByProxy(id, c);
  }

}