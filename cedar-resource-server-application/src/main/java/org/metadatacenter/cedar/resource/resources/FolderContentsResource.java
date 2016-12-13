package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/folders")
@Produces(MediaType.APPLICATION_JSON)
public class FolderContentsResource extends AbstractResourceServerResource {


  private static final String ROOT_PATH_BY_PATH = "folders/contents";
  private static final String ROOT_PATH_BY_ID = "folders/";

  public FolderContentsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @GET
  @Timed
  @Path("/folders/contents")
  public Response findFolderContentsByPath(@QueryParam("path") Optional<String> pathParam,
                                           @QueryParam("resource_types") Optional<String> resourceTypes,
                                           @QueryParam("sort") Optional<String> sort,
                                           @QueryParam("limit") Optional<Integer> limitParam,
                                           @QueryParam("offset") Optional<Integer> offsetParam) throws
      CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI absoluteURI = builder.queryParam("path", pathParam)
        .queryParam("resource_types", resourceTypes)
        .queryParam("sort", sort)
        .queryParam("limit", limitParam)
        .queryParam("offset", offsetParam)
        .build();

    String absoluteUrl = absoluteURI.toString();

    int idx = absoluteUrl.indexOf(ROOT_PATH_BY_PATH);
    String suffix = absoluteUrl.substring(idx);

    String url = folderBase + suffix;

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return deserializeAndConvertFolderNamesIfNecessary(proxyResponse);
  }

  @GET
  @Timed
  @Path("/folders/{id}/contents")
  public Response findFolderContentsById(@PathParam("id") String id,
                                         @QueryParam("resource_types") Optional<String> resourceTypes,
                                         @QueryParam("sort") Optional<String> sort,
                                         @QueryParam("limit") Optional<Integer> limitParam,
                                         @QueryParam("offset") Optional<Integer> offsetParam) throws
      CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI absoluteURI = builder
        .queryParam("resource_types", resourceTypes)
        .queryParam("sort", sort)
        .queryParam("limit", limitParam)
        .queryParam("offset", offsetParam)
        .build();

    String absoluteUrl = absoluteURI.toString();

    int idx = absoluteUrl.indexOf(ROOT_PATH_BY_ID);
    String suffix = absoluteUrl.substring(idx);

    String url = folderBase + suffix;

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return deserializeAndConvertFolderNamesIfNecessary(proxyResponse);
  }

  private Response deserializeAndConvertFolderNamesIfNecessary(HttpResponse proxyResponse) throws
      CedarAssertionException {

    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        FolderServerNodeListResponse response = null;
        String responseString = EntityUtils.toString(proxyResponse.getEntity());
        response = JsonMapper.MAPPER.readValue(responseString, FolderServerNodeListResponse.class);
        // it can not be deserialized as RSNodeListResponse
        if (response == null) {
          return Response.status(statusCode).entity(entity.getContent()).build();
        } else {
          return Response.status(statusCode).entity(JsonMapper.MAPPER.writeValueAsString(response)).build();
        }
      } catch (IOException e) {
        throw new CedarAssertionException(e);
      }
    } else {
      return Response.status(statusCode).build();
    }
  }
}