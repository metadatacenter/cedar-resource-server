package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

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
  public Response createTemplateInstance(Optional<Boolean> importMode) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE);

    CedarParameter folderIdP = c.request().getRequestBody().get("folderId");
    c.must(folderIdP).be(NonEmpty);

    String folderId = folderIdP.stringValue();

    if (!userHasWriteAccessToFolder(folderBase, folderId)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
          .errorMessage("You do not have write access to the folder")
          .parameter("folderId", folderId)
          .build();
    }
    return executeResourcePostByProxy(CedarNodeType.INSTANCE, importMode);
  }

  @ApiOperation(
      value = "Find template instance by id")
  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateInstance(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE_INSTANCE)
          .errorMessage("You do not have read access to the instance")
          .parameter("id", id)
          .build();
    }
    return executeResourceGetByProxy(CedarNodeType.INSTANCE, id);
  }

  @ApiOperation(
      value = "Find template instance details by id")
  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateInstanceDetails(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE_INSTANCE)
          .errorMessage("You do not have read access to the instance")
          .parameter("id", id)
          .build();
    }
    return executeResourceGetDetailsByProxy(CedarNodeType.INSTANCE, id);
  }

  @ApiOperation(
      value = "Update template instance")
  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateInstance(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE_INSTANCE)
          .errorMessage("You do not have write access to the template instance")
          .parameter("id", id)
          .build();
    }
    return executeResourcePutByProxy(CedarNodeType.INSTANCE, id);
  }

  @ApiOperation(
      value = "Delete template instance")
  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateInstance(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_DELETE);

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE_INSTANCE)
          .errorMessage("You do not have write access to the template instance")
          .parameter("id", id)
          .build();
    }
    return executeResourceDeleteByProxy(CedarNodeType.INSTANCE, id);
  }

  @ApiOperation(
      value = "Get permissions of a template instance")
  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateInstancePermissions(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE_INSTANCE)
          .errorMessage("You do not have read access to the template instance")
          .parameter("id", id)
          .build();
    }

    return executeResourcePermissionGetByProxy(id);
  }

  @ApiOperation(
      value = "Update template instance permissions")
  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateInstancePermissions(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE_INSTANCE)
          .errorMessage("You do not have write access to the template instance")
          .parameter("id", id)
          .build();
    }

    return executeResourcePermissionPutByProxy(id);
  }

}