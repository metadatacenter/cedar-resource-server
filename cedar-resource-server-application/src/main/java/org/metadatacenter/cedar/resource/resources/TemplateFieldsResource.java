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

@Path("/template-fields")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateFieldsResource extends AbstractResourceServerResource {

  public TemplateFieldsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplateField(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_CREATE);

    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.FIELD, folderId);
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetByProxyFromArtifactServer(CedarResourceType.FIELD, id, c);
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateFieldDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    return getDetails(c, id);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.FIELD, id);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_DELETE);

    return executeResourceDelete(c, CedarResourceType.FIELD, id);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateFieldPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    return generateNodePermissionsResponse(c, id);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateFieldPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);

    return updateNodePermissions(c, id);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateFieldInstanceReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    return generateNodeReportResponse(c, id);
  }

  @GET
  @Timed
  @Path("/{id}/versions")
  public Response getTemplateFieldVersions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    return generateNodeVersionsResponse(c, id);
  }

}
