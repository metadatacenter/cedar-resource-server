package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpResponse;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.CedarURIBuilder;
import org.metadatacenter.util.http.ProxyUtil;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/view")
@Produces(MediaType.APPLICATION_JSON)
public class SharedWithMeResource extends AbstractResourceServerResource {

  public SharedWithMeResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/shared-with-me")
  public Response copyResourceToFolder(@QueryParam("resource_types") Optional<String> resourceTypes,
                                       @QueryParam("sort") Optional<String> sort,
                                       @QueryParam("limit") Optional<Integer> limitParam,
                                       @QueryParam("offset") Optional<Integer> offsetParam) throws
      CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
        .queryParam("resource_types", resourceTypes)
        .queryParam("sort", sort)
        .queryParam("limit", limitParam)
        .queryParam("offset", offsetParam);

    String url = builder.getProxyUrl(folderBase);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return deserializeAndConvertFolderNamesIfNecessary(proxyResponse);
  }

}