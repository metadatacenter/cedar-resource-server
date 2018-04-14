package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.*;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SearchDeepResource extends AbstractSearchResource {

  public SearchDeepResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/search-deep")
  public Response searchDeep(@QueryParam(QP_Q) Optional<String> q,
                             @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
                             @QueryParam(QP_VERSION) Optional<String> versionParam,
                             @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
                             @QueryParam(QP_IS_BASED_ON) Optional<String> derivedFromIdParam,
                             @QueryParam(QP_SORT) Optional<String> sortParam,
                             @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                             @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
                             @QueryParam(QP_SHARING) Optional<String> sharing) throws CedarException {

    return super.search(q, resourceTypes, versionParam, publicationStatusParam, derivedFromIdParam, sortParam,
        limitParam, offsetParam, sharing, true);
  }
}