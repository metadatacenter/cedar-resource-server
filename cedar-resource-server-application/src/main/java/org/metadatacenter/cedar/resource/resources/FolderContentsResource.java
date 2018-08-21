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

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/folders")
@Produces(MediaType.APPLICATION_JSON)
public class FolderContentsResource extends AbstractResourceServerResource {

  public FolderContentsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/{id}/contents")
  public Response findFolderContentsById(@PathParam(PP_ID) String id,
                                         @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
                                         @QueryParam(QP_VERSION) Optional<String> versionParam,
                                         @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
                                         @QueryParam(QP_SORT) Optional<String> sort,
                                         @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                                         @QueryParam(QP_OFFSET) Optional<Integer> offsetParam) throws
      CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
        .queryParam(QP_RESOURCE_TYPES, resourceTypes)
        .queryParam(QP_VERSION, versionParam)
        .queryParam(QP_PUBLICATION_STATUS, publicationStatusParam)
        .queryParam(QP_SORT, sort)
        .queryParam(QP_LIMIT, limitParam)
        .queryParam(QP_OFFSET, offsetParam);

    String url = builder.getProxyUrl(microserviceUrlUtil.getWorkspace().getBase());

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return deserializeAndAddProvenanceDisplayNames(proxyResponse, c);
  }

}