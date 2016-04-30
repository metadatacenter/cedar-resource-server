package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.response.RSNodeListResponse;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class SearchService implements ISearchService {

  private ElasticsearchService esService;

  public SearchService(ElasticsearchService esService) {
    this.esService = esService;
  }

  public void addToIndex(CedarIndexResource resource) throws Exception {
    JsonNode jsonResource = new ObjectMapper().convertValue(resource, JsonNode.class);
    esService.addToIndex(jsonResource);
  }

  public void removeFromIndex(String resourceId) throws Exception {
    esService.removeFromIndex(resourceId);
  }

  public RSNodeListResponse search(String query, List<String> resourceTypes) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    SearchResponse esResults = esService.search(query, resourceTypes);
    RSNodeListResponse response = new RSNodeListResponse();
    List<CedarRSNode> resources = new ArrayList<>();
    for (SearchHit hit : esResults.getHits()) {
      String hitJson = hit.sourceAsString();
      CedarIndexResource resource = mapper.readValue(hitJson, CedarIndexResource.class);
      resources.add(resource.getInfo());
    }
    response.setTotalCount(esResults.getHits().getTotalHits());
    response.setResources(resources);
    return response;

  }

}
