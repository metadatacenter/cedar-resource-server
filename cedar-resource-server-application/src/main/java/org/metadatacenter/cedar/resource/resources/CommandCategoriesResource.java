package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.util.http.CedarResponse;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandCategoriesResource extends AbstractResourceServerResource {

  public CommandCategoriesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/attach-category")
  public Response attachCategoryToArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter artifactIdParam = c.request().getRequestBody().get("artifactId");
    CedarParameter categoryIdParam = c.request().getRequestBody().get("categoryId");

    c.must(artifactIdParam).be(NonEmpty);
    c.must(categoryIdParam).be(NonEmpty);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    String artifactId = artifactIdParam.stringValue();
    String categoryId = categoryIdParam.stringValue();

    CedarCategoryId ccid = CedarCategoryId.build(categoryId);

    FolderServerArtifactCurrentUserReport folderServerResource = userMustHaveWriteAccessToArtifact(c, artifactId);

    FolderServerCategory category = userMustHaveAttachAccessToCategory(c, ccid);

    boolean attached = categorySession.attachCategoryToArtifact(ccid, artifactId);
    if (attached) {
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
      FolderServerArtifact updatedResource = folderSession.findArtifactById(artifactId);
      updateIndexResource(updatedResource, c);
      return Response.ok().entity(folderServerResource).build();
    } else {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.UNABLE_TO_ATTACH_CATEGORY)
          .errorMessage("The category was not attached to the artifact")
          .parameter("categoryId", categoryId)
          .parameter("artifactId", artifactId)
          .build();
    }
  }

  @POST
  @Timed
  @Path("/detach-category")
  public Response detachCategoryFromArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter artifactIdParam = c.request().getRequestBody().get("artifactId");
    CedarParameter categoryIdParam = c.request().getRequestBody().get("categoryId");

    c.must(artifactIdParam).be(NonEmpty);
    c.must(categoryIdParam).be(NonEmpty);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);

    String artifactId = artifactIdParam.stringValue();
    String categoryId = categoryIdParam.stringValue();

    CedarCategoryId ccid = CedarCategoryId.build(categoryId);

    FolderServerArtifactCurrentUserReport folderServerResource = userMustHaveWriteAccessToArtifact(c, artifactId);

    FolderServerCategory category = userMustHaveAttachAccessToCategory(c, ccid);

    boolean attached = categorySession.detachCategoryFromArtifact(ccid, artifactId);
    if (attached) {
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
      FolderServerArtifact updatedResource = folderSession.findArtifactById(artifactId);
      updateIndexResource(updatedResource, c);
      return Response.ok().entity(folderServerResource).build();
    } else {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.UNABLE_TO_DETACH_CATEGORY)
          .errorMessage("The category was not detached from the artifact")
          .parameter("categoryId", categoryId)
          .parameter("artifactId", artifactId)
          .build();
    }
  }

}
