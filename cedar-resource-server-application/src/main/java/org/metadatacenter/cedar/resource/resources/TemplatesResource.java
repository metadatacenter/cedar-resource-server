package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
public class TemplatesResource extends AbstractResourceServerResource {

  public TemplatesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createTemplate(@QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_CREATE);
    return executeResourceCreationOnArtifactServerAndGraphDb(c, CedarResourceType.TEMPLATE, Optional.empty(), folderId);
  }

  @GET
  @Timed
  @Path("/{id}")
  @Produces({MediaType.APPLICATION_JSON, "application/x-yaml"})
  public Response findTemplate(@PathParam(PP_ID) String id,
                               @HeaderParam("Accept") String acceptHeader,
                               @QueryParam("compact") Optional<Boolean> compactParam,
                               @QueryParam("download") Optional<Boolean> downloadParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveReadAccessToArtifact(c, tid);
    if (acceptHeader.isEmpty() || acceptHeader.contains(MediaType.APPLICATION_JSON)) {
      return executeResourceGetByProxyFromArtifactServer(CedarResourceType.TEMPLATE, id, c);
    }
    if (acceptHeader.contains("application/x-yaml")) {
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
      CedarTemplateId templateId = CedarTemplateId.build(id);
      FolderServerArtifact template = folderSession.findArtifactById(templateId);
    }
    return CedarResponse.badRequest()
        .errorMessage("You passed an invalid Accept header: '" + acceptHeader + "'")
        .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
        .parameter("Accept", acceptHeader)
        .parameter("allowed Accept headers", Arrays.toString(new String[]{MediaType.APPLICATION_JSON, "application/x-yaml"}))
        .build();
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return getDetails(c, tid);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplate(@PathParam(PP_ID) String id, @QueryParam(QP_FOLDER_ID) Optional<String> folderId) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return executeResourceCreateOrUpdateViaPut(c, CedarResourceType.TEMPLATE, tid, folderId);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_DELETE);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return executeArtifactDelete(c, CedarResourceType.TEMPLATE, tid);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplatePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return generateResourcePermissionsResponse(c, tid);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplatePermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return updateResourcePermissions(c, tid);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return generateArtifactReportResponse(c, tid);
  }

  @GET
  @Timed
  @Path("/{id}/versions")
  public Response getTemplateVersions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    return generateNodeVersionsResponse(c, tid);
  }

}
