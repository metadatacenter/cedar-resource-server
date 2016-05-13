package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.response.RSNodeListResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class SearchService implements ISearchService {

  private ElasticsearchService esService;
  private String esIndex;
  private String esType;

  public SearchService(ElasticsearchService esService, String esIndex, String esType) {
    this.esService = esService;
    this.esIndex = esIndex;
    this.esType = esType;
  }

  public SearchService(ElasticsearchService esService) {
    this.esService = esService;
  }

  public void initializeSearchIndex() throws IOException {
    esService.createIndex(esIndex);
  }

  public void addToIndex(CedarIndexResource resource) throws IOException {
    JsonNode jsonResource = new ObjectMapper().convertValue(resource, JsonNode.class);
    esService.addToIndex(jsonResource, esIndex, esType);
  }

  public void removeFromIndex(String resourceId) throws IOException {
    esService.removeFromIndex(resourceId, esIndex, esType);
  }

  public RSNodeListResponse search(String query, List<String> resourceTypes) throws IOException {
    // Create index if it does not exist

    ObjectMapper mapper = new ObjectMapper();
    SearchResponse esResults = esService.search(query, resourceTypes, esIndex, esType);
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

  // Implement this at the ES service level
//  private int getNumberOfIndexedResources() {
//    return 0;
//  }

  public void regenerateSearchIndex(List<CedarIndexResource> resources, boolean force) throws IOException {
    boolean regenerate = true;
//    if (!force) {
//      if (resources.size() == getNumberOfIndexedResources()) {
//        reindex = false;
//      }
//    }
    if (regenerate) {
      // Create new index and set it up
      String newIndexName = esIndex + "-" + Long.toString(Calendar.getInstance().getTimeInMillis());
      // Create the index and add alias
      createSearchIndex(newIndexName, esType);


      // Index all resources
//      for (CedarIndexResource r : resources) {
//      }

      // Point alias to new index
      esService.addAlias(newIndexName, esIndex);

      // Delete any other index previously associated to the alias
      List<String> indexNames = esService.getIndexesByAlias(esIndex);
      for (String indexName : indexNames) {
        if (indexName.compareTo(newIndexName)!=0) {
          esService.deleteIndex(indexName);
        }
      }
    }
  }

  private void createSearchIndex(String indexName, String documentType) throws IOException {
    // Mapping definition for search index. The info.@id field is set as not analyzed
    XContentBuilder mapping = XContentFactory.jsonBuilder()
        .startObject()
        .startObject(documentType)
        .startObject("properties")
        .startObject("info")
        .startObject("properties")
        .startObject("@id")
        .field("type", "string")
        .field("index", "not_analyzed")
        .endObject()
        .endObject()
        .endObject()
        .endObject()
        .endObject()
        .endObject();
    esService.createIndex(indexName, documentType, mapping);
  }
}
