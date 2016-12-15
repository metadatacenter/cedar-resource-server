package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
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
  public Response createTemplateElement(Optional<Boolean> importMode) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_CREATE);

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
    return executeResourcePostByProxy(CedarNodeType.ELEMENT, importMode);
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

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE_ELEMENT)
          .errorMessage("You do not have read access to the element")
          .parameter("id", id)
          .build();
    }
    return executeResourceGetByProxy(CedarNodeType.ELEMENT, id);
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

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE_ELEMENT)
          .errorMessage("You do not have read access to the element")
          .parameter("id", id)
          .build();
    }
    return executeResourceGetDetailsByProxy(CedarNodeType.ELEMENT, id);
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

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE_ELEMENT)
          .errorMessage("You do not have write access to the template element")
          .parameter("id", id)
          .build();
    }
    return executeResourcePutByProxy(CedarNodeType.ELEMENT, id);
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

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE_ELEMENT)
          .errorMessage("You do not have write access to the template element")
          .parameter("id", id)
          .build();
    }
    return executeResourceDeleteByProxy(CedarNodeType.ELEMENT, id);
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

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_TEMPLATE_ELEMENT)
          .errorMessage("You do not have read access to the template element")
          .parameter("id", id)
          .build();
    }

    return executeResourcePermissionGetByProxy(id);
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

    if (!userHasWriteAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_TEMPLATE_ELEMENT)
          .errorMessage("You do not have write access to the template element")
          .parameter("id", id)
          .build();
    }

    return executeResourcePermissionPutByProxy(id);
  }

}