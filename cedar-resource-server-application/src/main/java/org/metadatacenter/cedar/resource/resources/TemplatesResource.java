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

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

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
  public Response createTemplate(Optional<Boolean> importMode) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_CREATE);

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
    return executeResourcePostByProxy(CedarNodeType.TEMPLATE, importMode);
  }

  @ApiOperation(
      value = "Find template by id")
  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplate(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE)
          .errorMessage("You do not have read access to the template")
          .parameter("id", id)
          .build();
    }
    return executeResourceGetByProxy(CedarNodeType.TEMPLATE, id);
  }

  @ApiOperation(
      value = "Find template details by id")
  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateDetails(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE)
          .errorMessage("You do not have read access to the template")
          .parameter("id", id)
          .build();
    }
    return executeResourceGetDetailsByProxy(CedarNodeType.TEMPLATE, id);
  }

  @ApiOperation(
      value = "Update template")
  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplate(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE)
          .errorMessage("You do not have write access to the template")
          .parameter("id", id)
          .build();
    }
    return executeResourcePutByProxy(CedarNodeType.TEMPLATE, id);
  }

  @ApiOperation(
      value = "Delete template")
  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplate(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_DELETE);

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE)
          .errorMessage("You do not have write access to the template")
          .parameter("id", id)
          .build();
    }
    return executeResourceDeleteByProxy(CedarNodeType.TEMPLATE, id);
  }

  @ApiOperation(
      value = "Get permissions of a template")
  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplatePermissions(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE)
          .errorMessage("You do not have read access to the template")
          .parameter("id", id)
          .build();
    }

    return executeResourcePermissionGetByProxy(id);
  }

  @ApiOperation(
      value = "Update template permissions")
  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplatePermissions(@PathParam("id") String id) throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE)
          .errorMessage("You do not have write access to the template")
          .parameter("id", id)
          .build();
    }

    return executeResourcePermissionPutByProxy(id);
  }
}