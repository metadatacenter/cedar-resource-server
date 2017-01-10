package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpResponse;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.util.http.CedarURIBuilder;
import org.metadatacenter.util.http.ProxyUtil;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/folders")
@Produces(MediaType.APPLICATION_JSON)
public class FolderContentsResource extends AbstractResourceServerResource {

  public FolderContentsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/contents")
  public Response findFolderContentsByPath(@QueryParam("path") Optional<String> pathParam,
                                           @QueryParam("resource_types") Optional<String> resourceTypes,
                                           @QueryParam("sort") Optional<String> sort,
                                           @QueryParam("limit") Optional<Integer> limitParam,
                                           @QueryParam("offset") Optional<Integer> offsetParam) throws
      CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
        .queryParam("path", pathParam)
        .queryParam("resource_types", resourceTypes)
        .queryParam("sort", sort)
        .queryParam("limit", limitParam)
        .queryParam("offset", offsetParam);

    String url = builder.getProxyUrl(folderBase);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return deserializeAndConvertFolderNamesIfNecessary(proxyResponse);
  }

  @GET
  @Timed
  @Path("/{id}/contents")
  public Response findFolderContentsById(@PathParam("id") String id,
                                         @QueryParam("resource_types") Optional<String> resourceTypes,
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