package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandOpenResource extends AbstractResourceServerResource {

  public CommandOpenResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/make-artifact-open")
  public Response makeArtifactOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarUntypedArtifactId artifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToArtifact(c, artifactId);

    folderSession.setOpen(artifactId);
    FolderServerArtifact updatedResource = folderSession.findArtifactById(artifactId);
    return Response.ok().entity(updatedResource).build();
  }

  @POST
  @Timed
  @Path("/make-artifact-not-open")
  public Response makeArtifactNotOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarUntypedArtifactId artifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToArtifact(c, artifactId);

    folderSession.setNotOpen(artifactId);
    FolderServerArtifact updatedResource = folderSession.findArtifactById(artifactId);
    return Response.ok().entity(updatedResource).build();
  }

  @POST
  @Timed
  @Path("/make-folder-open")
  public Response makeFolderOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarFolderId folderId = CedarFolderId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToFolder(c, folderId);

    folderSession.setOpen(folderId);
    FolderServerFolder updatedFolder = folderSession.findFolderById(folderId);
    return Response.ok().entity(updatedFolder).build();
  }

  @POST
  @Timed
  @Path("/make-folder-not-open")
  public Response makeFolderNotOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarFolderId folderId = CedarFolderId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToFolder(c, folderId);

    folderSession.setNotOpen(folderId);
    FolderServerFolder updatedFolder = folderSession.findFolderById(folderId);
    return Response.ok().entity(updatedFolder).build();
  }

}
