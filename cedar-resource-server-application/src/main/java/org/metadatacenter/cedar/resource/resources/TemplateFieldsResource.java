package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerResource;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.ValidField;

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
    c.must(c.request()).be(ValidField);

    String folderIdS;

    CedarParameter folderIdP = c.request().wrapQueryParam(QP_FOLDER_ID, folderId);
    if (folderIdP.isEmpty()) {
      folderIdS = c.getCedarUser().getHomeFolderId();
    } else {
      folderIdS = folderIdP.stringValue();
    }

    FolderServerFolder folder = userMustHaveWriteAccessToFolder(c, folderIdS);
    return executeResourcePostByProxy(c, CedarNodeType.FIELD, folder);
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetByProxy(CedarNodeType.FIELD, id, c);
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findTemplateFieldDetails(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceGetDetailsByProxy(CedarNodeType.FIELD, id, c);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);
    c.must(c.request()).be(ValidField);

    FolderServerResource folderServerResource = userMustHaveWriteAccessToResource(c, id);
    return executeResourcePutByProxy(c, CedarNodeType.FIELD, id, folderServerResource);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_DELETE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourceDeleteByProxy(c, CedarNodeType.FIELD, id);
  }

  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getTemplateFieldPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourcePermissionGetByProxy(id, c);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateTemplateFieldPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);

    userMustHaveWriteAccessToResource(c, id);
    return executeResourcePermissionPutByProxy(id, c);
  }

  @GET
  @Timed
  @Path("/{id}/report")
  public Response getTemplateFieldInstanceReport(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceReportGetByProxy(id, c);
  }

  @GET
  @Timed
  @Path("/{id}/versions")
  public Response getTemplateFieldVersions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    userMustHaveReadAccessToResource(c, id);
    return executeResourceVersionsGetByProxy(c, id);
  }

}