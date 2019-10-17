package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.util.http.CedarResponse;

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
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerArtifactCurrentUserReport resourceReport = userMustHaveWriteAccessToArtifact(c, id);

    if (resourceReport != null) {
      folderSession.setOpen(id);
      FolderServerArtifact updatedResource = folderSession.findArtifactById(id);
      return Response.ok().entity(updatedResource).build();
    } else {
      return CedarResponse.notFound().build();
    }
  }

  @POST
  @Timed
  @Path("/make-artifact-not-open")
  public Response makeArtifactNotOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerArtifactCurrentUserReport resourceReport = userMustHaveWriteAccessToArtifact(c, id);

    if (resourceReport != null) {
      folderSession.setNotOpen(id);
      FolderServerArtifact updatedResource = folderSession.findArtifactById(id);
      return Response.ok().entity(updatedResource).build();
    } else {
      return CedarResponse.notFound().build();
    }

  }
}
