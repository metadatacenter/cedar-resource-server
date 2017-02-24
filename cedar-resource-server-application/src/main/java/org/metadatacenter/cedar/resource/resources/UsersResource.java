package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.folderserver.FolderServerUser;
import org.metadatacenter.model.response.FolderServerUserListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.util.http.CedarEntityUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
public class UsersResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(UsersResource.class);

  public UsersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  public Response findUsers() throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    String url = usersURL;
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    HttpEntity entity = proxyResponse.getEntity();
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    if (statusCode == HttpStatus.SC_OK) {
      if (entity != null) {
        FolderServerUserListResponse folderServerUserListResponse = null;
        try {
          folderServerUserListResponse = JsonMapper.MAPPER.readValue(entity.getContent(),
              FolderServerUserListResponse.class);
        } catch (IOException e) {
          log.error("There was an error deserializing the user list", e);
        }
        if (folderServerUserListResponse != null) {
          for (FolderServerUser u : folderServerUserListResponse.getUsers()) {
            u.setEmail(null);
          }
          return Response.status(statusCode).entity(folderServerUserListResponse).build();
        }
      }
    }
    if (entity != null) {
      return Response.status(statusCode).entity(CedarEntityUtil.toString(entity)).build();
    } else {
      return Response.status(statusCode).build();
    }
  }
}