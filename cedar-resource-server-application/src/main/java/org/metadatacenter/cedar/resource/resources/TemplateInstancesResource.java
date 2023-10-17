package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarTemplateInstanceId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/template-instances")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateInstancesResource extends AbstractResourceServerResource {

  public TemplateInstancesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplateInstance(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.INSTANCE, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateInstance(@PathParam(PP_ID) String id, @QueryParam(QP_FORMAT) Optional<String> format) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    userMustHaveReadAccessToArtifact(c, tiid);
    return executeResourceGetByProxyFromArtifactServer(CedarResourceType.INSTANCE, id, format, c);
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateInstanceDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return getDetails(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateInstance(@PathParam(PP_ID) String id, @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.INSTANCE, tiid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateInstance(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_DELETE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return executeArtifactDelete(c, CedarResourceType.INSTANCE, tiid);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateInstancePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateResourcePermissionsResponse(c, tiid);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateInstancePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_UPDATE);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return updateResourcePermissions(c, tiid);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateInstanceReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ);
    CedarTemplateInstanceId tiid = CedarTemplateInstanceId.build(id);

    return generateArtifactReportResponse(c, tiid);
  }

}
