package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.annotations.ApiOperation;
import org.metadatacenter.cedar.resource.util.ParametersValidator;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SearchDeepResource extends AbstractResourceServerResource {

  public SearchDeepResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "Search for resources. This call is not paged and is not intended for real time user requests, but " +
          "rather" +
          " for processing large amounts of data.", httpMethod = "GET")
  @GET
  @Timed
  @Path("/search-deep")
  public Response searchDeep(@QueryParam("query") Optional<String> query,
                             @QueryParam("resource_types") Optional<String> resourceTypes,
                             @QueryParam("template_id") Optional<String> templateId,
                             @QueryParam("sort") Optional<String> sort,
                             @QueryParam("offset") Optional<Integer> limitParam) throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    try {
      // Parameters validation
      String queryString = ParametersValidator.validateQuery(query);
      String tempId = ParametersValidator.validateTemplateId(templateId);
      // If templateId is specified, the resource types is limited to instances
      List<String> resourceTypeList = null;
      if (tempId != null) {
        resourceTypeList = new ArrayList<>();
        resourceTypeList.add(CedarNodeType.Types.INSTANCE);
      } else {
        resourceTypeList = ParametersValidator.validateResourceTypes(resourceTypes);
      }
      List<String> sortList = ParametersValidator.validateSort(sort);
      // The searchDeep method can be used to retrieve all elements in one call, so we set the highest possible
      // maxAllowedLimit
      int limit = ParametersValidator.validateLimit(limitParam,
          cedarConfig.getSearchSettings().getSearchDefaultSettings().getDefaultLimit(), Integer.MAX_VALUE);

      FolderServerNodeListResponse results = searchService.searchDeep(queryString, resourceTypeList, tempId,
          sortList, limit);

      JsonNode resultsNode = JsonMapper.MAPPER.valueToTree(results);
      return Response.ok().entity(resultsNode).build();
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }
  }
}