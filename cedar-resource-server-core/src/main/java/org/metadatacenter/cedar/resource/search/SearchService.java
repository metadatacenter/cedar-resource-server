package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.EncoderException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.cedar.resource.search.util.IndexUtils;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.exception.CedarAccessException;

import java.io.IOException;
import java.util.*;

public class SearchService implements ISearchService {

  private ElasticsearchService esService;
  private String esIndex;
  private String esType;

  public SearchService(ElasticsearchService esService, String esIndex, String esType) {
    this.esService = esService;
    this.esIndex = esIndex;
    this.esType = esType;
  }

  public void indexResource(CedarRSNode resource, JsonNode resourceContent, String indexName, String documentType) throws IOException {
    List<String> fieldNames = new ArrayList<>();
    List<String> fieldValues = new ArrayList<>();
    if (resourceContent !=null) {
      fieldNames = IndexUtils.extractFieldNames(resource.getType(), resourceContent, new ArrayList<>());
      fieldValues = IndexUtils.extractFieldValues(resource.getType(), resourceContent, new ArrayList<>());
    }
    System.out.println("Indexing resource (id = " + resource.getId() + ")");
    CedarIndexResource ir = new CedarIndexResource(resource, fieldNames, fieldValues);
    JsonNode jsonResource = new ObjectMapper().convertValue(ir, JsonNode.class);
    esService.addToIndex(jsonResource, indexName, documentType);
  }

  public void indexResource(CedarRSNode resource, JsonNode resourceContent) throws IOException {
    // Use default index and type
    indexResource(resource, resourceContent, esIndex, esType);
  }

  public void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws IOException {
    System.out.println("Removing resource from index (id = " + resourceId);
    esService.removeFromIndex(resourceId, indexName, documentType);
  }

  public void removeResourceFromIndex(String resourceId) throws IOException {
    // Use default index and type
    removeResourceFromIndex(resourceId, esIndex, esType);
  }

  public void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent, String indexName, String
      documentType) throws IOException {
    List<String> fieldNames = new ArrayList<>();
    List<String> fieldValues = new ArrayList<>();
    if (resourceContent !=null) {
      fieldNames = IndexUtils.extractFieldNames(newResource.getType(), resourceContent, new ArrayList<>());
      fieldValues = IndexUtils.extractFieldValues(newResource.getType(), resourceContent, new ArrayList<>());
    }
    System.out.println("Updating resource (id = " + newResource.getId());
    removeResourceFromIndex(newResource.getId(), indexName, documentType);
    addToIndex(new CedarIndexResource(newResource, fieldNames, fieldValues), indexName, documentType);
  }

  public void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent) throws IOException {
    // Use default index and type
    updateIndexedResource(newResource, resourceContent, esIndex, esType);
  }

  public RSNodeListResponse search(String query, List<String> resourceTypes) throws IOException {
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

//  public void initializeSearchIndex() throws IOException {
//    // If search index does not exist, generate it
//    if (!esService.indexExists(esIndex)) {
//      System.out.println("THE SEARCH INDEX DOES NOT EXIST");
//      regenerateSearchIndex();
//    }
//    else {
//      System.out.println("THE SEARCH INDEX EXISTS");
//    }
//  }

  public void regenerateSearchIndex(boolean force, String apiKey) throws IOException, CedarAccessException, EncoderException, InterruptedException {
    System.out.println("Checking if it is necessary to regenerate the search index from DB");
    boolean regenerate = true;
    // Get all resources
    List<CedarRSNode> resources = IndexUtils.findAllResources(apiKey);
    // Checks if is necessary to regenerate the index or not
    if (!force) {
        // Check if the index exists (using the alias). If it exists, check if it contains all resources
      if (esService.indexExists(esIndex)) {
        // Use the resource ids to check if the resources in the DBs and in the index are different
        List<String> dbResourceIds = esService.findAll("info.@id", esIndex, esType);
        System.out.println("No. of resources in DB that are expected to be indexed: " + dbResourceIds.size());
        List<String> indexResourceIds = getResourceIds(resources);
        System.out.println("No. of resources in the index: " + indexResourceIds.size());
        if (dbResourceIds.size() == indexResourceIds.size()) {
          // Compare the two lists
          List<String> tmp = new ArrayList(dbResourceIds);
          tmp.removeAll(indexResourceIds);
          if (tmp.size() == 0) {
            regenerate = false;
            System.out.println("DB and search index match. It is not necessary to regenerate the index");
          }
          else {
            System.out.println("DB and search index do not match!");
          }
        }
        else {
          System.out.println("DB and search index do not match! (different size)");
        }
      }
      else {
        System.out.println("The search index '" + esIndex + "' does not exist!");
      }
    }
    if (regenerate) {
      System.out.println("Regenerating index");
      // Get resources content
      Map<String, JsonNode> resourcesContent = new HashMap<>();
      for (CedarRSNode resource : resources) {
        if (resource.getType() != CedarNodeType.FOLDER) {
          JsonNode resourceContent = IndexUtils.findResourceContent(resource.getId(), resource.getType(), apiKey);
          resourcesContent.put(resource.getId(), resourceContent);
        }
      }
      // Create new index and set it up
      String newIndexName = esIndex + "-" + Long.toString(Calendar.getInstance().getTimeInMillis());
      createSearchIndex(newIndexName, esType);
      // Index all resources
      if (resources != null) {
        for (CedarRSNode resource : resources) {
          JsonNode resourceContent = null;
          if (resourcesContent != null) {
            resourceContent = resourcesContent.get(resource.getId());
          }
          indexResource(resource, resourceContent, newIndexName, esType);
        }
      }
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

  /**
   * PRIVATE METHODS
   */

  private List<String> getResourceIds(List<CedarRSNode> resources) {
    List<String> ids = new ArrayList<>();
    for (CedarRSNode resource : resources) {
      ids.add(resource.getId());
    }
    return ids;
  }

  private void addToIndex(CedarIndexResource resource, String indexName, String documentType) throws IOException {
    JsonNode resourceJson = new ObjectMapper().valueToTree(resource);
    esService.addToIndex(resourceJson, indexName, documentType);
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
