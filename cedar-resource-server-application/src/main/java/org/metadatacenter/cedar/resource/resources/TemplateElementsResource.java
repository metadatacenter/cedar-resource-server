package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-elements")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateElementsResource extends AbstractResourceServerResource {

  public TemplateElementsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplateElement(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_CREATE);

    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.ELEMENT, folderId);
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateElement(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetByProxyFromArtifactServer(CedarResourceType.ELEMENT, id, c);
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateElementDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    return getDetails(c, id);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateElement(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_UPDATE);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.ELEMENT, id);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateElement(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_DELETE);

    return executeResourceDelete(c, CedarResourceType.ELEMENT, id);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateElementPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    return generateNodePermissionsResponse(c, id);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateElementPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_UPDATE);

    return updateNodePermissions(c, id);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateElementInstanceReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    return generateNodeReportResponse(c, id);
  }

  @GET
  @Timed
  @Path("/{id}/versions")
  public Response getTemplateElementVersions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_ELEMENT_READ);

    return generateNodeVersionsResponse(c, id);
  }

}
