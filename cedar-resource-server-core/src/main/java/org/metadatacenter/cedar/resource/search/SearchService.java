package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.cedar.resource.search.util.IndexUtils;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.util.http.LinkHeaderUtil;

import java.io.IOException;
import java.util.*;

import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_PREFIX;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_ID_FIELD;

public class SearchService implements ISearchService {

  private ElasticsearchService esService;
  private String esIndex;
  private String esType;
  private IndexUtils indexUtils;

  // TODO: folderBase, templateBase, limit, maxAttemps and delayAttemps should ideally come from a centralized
  // configuration solution instead of passing them to the constructor. IndexUtils should be able to directly read
  // those parameters and it would not be necessary to create an instance of it. Static methods could be used instead.
  public SearchService(ElasticsearchService esService, String esIndex, String esType, String folderBase, String
      templateBase, int limit, int maxAttemps, int delayAttemps) {
    this.esService = esService;
    this.esIndex = esIndex;
    this.esType = esType;
    this.indexUtils = new IndexUtils(folderBase, templateBase, limit, maxAttemps, delayAttemps);
  }

  public void indexResource(CedarRSNode resource, JsonNode resourceContent, String indexName, String documentType, IAuthRequest authRequest)
      throws IOException, CedarAccessException, EncoderException {
    List<String> fieldNames = new ArrayList<>();
    List<String> fieldValues = new ArrayList<>();
    if (resourceContent != null) {
      fieldNames = indexUtils.extractFieldNames(resource.getType(), resourceContent, new ArrayList<>(), authRequest);
      fieldValues = indexUtils.extractFieldValues(resource.getType(), resourceContent, new ArrayList<>());
    }
    System.out.println("Indexing resource (id = " + resource.getId() + ")");
    // Set resource details
    String templateId = null;
    if (CedarNodeType.INSTANCE.equals(resource.getType())) {
      templateId = resourceContent.get("schema:isBasedOn").asText();
    }
    resource = setResourceDetails(resource);
    CedarIndexResource ir = new CedarIndexResource(resource, fieldNames, fieldValues, templateId);
    JsonNode jsonResource = new ObjectMapper().convertValue(ir, JsonNode.class);
    esService.addToIndex(jsonResource, indexName, documentType);
  }

  public void indexResource(CedarRSNode resource, JsonNode resourceContent, IAuthRequest authRequest) throws
      IOException, CedarAccessException, EncoderException {
    // Use default index and type
    indexResource(resource, resourceContent, esIndex, esType, authRequest);
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
      documentType, IAuthRequest authRequest) throws IOException, CedarAccessException, EncoderException {
    List<String> fieldNames = new ArrayList<>();
    List<String> fieldValues = new ArrayList<>();
    if (resourceContent != null) {
      fieldNames = indexUtils.extractFieldNames(newResource.getType(), resourceContent, new ArrayList<>(), authRequest);
      fieldValues = indexUtils.extractFieldValues(newResource.getType(), resourceContent, new ArrayList<>());
    }
    System.out.println("Updating resource (id = " + newResource.getId());
    removeResourceFromIndex(newResource.getId(), indexName, documentType);
    String templateId = null;
    if (CedarNodeType.INSTANCE.equals(newResource.getType())) {
      templateId = resourceContent.get("schema:isBasedOn").asText();
    }
    newResource = setResourceDetails(newResource);
    addToIndex(new CedarIndexResource(newResource, fieldNames, fieldValues, templateId), indexName, documentType);
  }

  public void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent, IAuthRequest authRequest)
      throws IOException, CedarAccessException, EncoderException {
    // Use default index and type
    updateIndexedResource(newResource, resourceContent, esIndex, esType, authRequest);
  }

  public RSNodeListResponse search(String query, List<String> resourceTypes, String templateId, List<String> sortList, int limit, int offset, String absoluteUrl) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    SearchResponse esResults = esService.search(query, resourceTypes, sortList, templateId, esIndex, esType, limit, offset);
    RSNodeListResponse response = new RSNodeListResponse();
    List<CedarRSNode> resources = new ArrayList<>();
    for (SearchHit hit : esResults.getHits()) {
      String hitJson = hit.sourceAsString();
      CedarIndexResource resource = mapper.readValue(hitJson, CedarIndexResource.class);
      resources.add(resource.getInfo());
    }
    long total = esResults.getHits().getTotalHits();
    response.setTotalCount(total);
    response.setCurrentOffset(offset);
    response.setPaging(LinkHeaderUtil.getPagingLinkHeaders(absoluteUrl, total, limit, offset));
    response.setResources(resources);

    List<CedarNodeType> nodeTypeList = new ArrayList<>();
    if (resourceTypes != null) {
      for (String rt : resourceTypes) {
        nodeTypeList.add(CedarNodeType.forValue(rt));
      }
    }
    NodeListRequest req = new NodeListRequest();
    req.setNodeTypes(nodeTypeList);
    req.setLimit(limit);
    req.setOffset(offset);
    req.setSort(sortList);
    response.setRequest(req);

    return response;
  }

  public RSNodeListResponse searchDeep(String query, List<String> resourceTypes, String templateId, List<String> sortList, int limit) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<SearchHit> esHits = esService.searchDeep(query, resourceTypes, sortList, templateId, esIndex, esType, limit);

    // Apply limit
    if (esHits.size() >= limit) {
      esHits = esHits.subList(0, limit);
    }

    RSNodeListResponse response = new RSNodeListResponse();
    List<CedarRSNode> resources = new ArrayList<>();
    for (SearchHit hit : esHits) {
      String hitJson = hit.sourceAsString();
      CedarIndexResource resource = mapper.readValue(hitJson, CedarIndexResource.class);
      resources.add(resource.getInfo());
    }

    response.setTotalCount(resources.size());
    response.setResources(resources);

    List<CedarNodeType> nodeTypeList = new ArrayList<>();
    if (resourceTypes != null) {
      for (String rt : resourceTypes) {
        nodeTypeList.add(CedarNodeType.forValue(rt));
      }
    }
    NodeListRequest req = new NodeListRequest();
    req.setNodeTypes(nodeTypeList);
    req.setLimit(limit);
    req.setSort(sortList);
    response.setRequest(req);

    return response;
  }

  public void regenerateSearchIndex(boolean force, IAuthRequest authRequest) throws IOException, CedarAccessException,
      EncoderException, InterruptedException {
    boolean regenerate = true;
    // Get all resources
    List<CedarRSNode> resources = indexUtils.findAllResources(authRequest);
    // Checks if is necessary to regenerate the index or not
    if (!force) {
      System.out.println("Checking if it is necessary to regenerate the search index from DB");
      // Check if the index exists (using the alias). If it exists, check if it contains all resources
      if (esService.indexExists(esIndex)) {
        // Use the resource ids to check if the resources in the DBs and in the index are different
        List<String> dbResourceIds = getResourceIds(resources);
        System.out.println("No. of resources in DB that are expected to be indexed: " + dbResourceIds.size());
        List<String> indexResourceIds = esService.findAllValuesForField(ES_RESOURCE_PREFIX + ES_RESOURCE_ID_FIELD, esIndex, esType);
        System.out.println("No. of resources in the index: " + indexResourceIds.size());
        if (dbResourceIds.size() == indexResourceIds.size()) {
          // Compare the two lists
          List<String> tmp1 = new ArrayList(dbResourceIds);
          List<String> tmp2 = new ArrayList(indexResourceIds);
          Collections.sort(tmp1);
          Collections.sort(tmp2);
          if (tmp1.equals(tmp2)) {
            regenerate = false;
            System.out.println("DB and search index match. It is not necessary to regenerate the index");
          } else {
            System.out.println("DB and search index do not match!");
          }
        } else {
          System.out.println("DB and search index do not match! (different size)");
        }
      } else {
        System.out.println("The search index '" + esIndex + "' does not exist!");
      }
    }
    if (regenerate) {
      System.out.println("Regenerating index");
      // Get resources content
      Map<String, JsonNode> resourcesContent = new HashMap<>();
      for (CedarRSNode resource : resources) {
        if (resource.getType() != CedarNodeType.FOLDER) {
          JsonNode resourceContent = indexUtils.findResourceContent(resource.getId(), resource.getType(), authRequest);
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
          indexResource(resource, resourceContent, newIndexName, esType, authRequest);
        }
      }
      // Point alias to new index
      esService.addAlias(newIndexName, esIndex);

      // Delete any other index previously associated to the alias
      List<String> indexNames = esService.getIndexesByAlias(esIndex);
      for (String indexName : indexNames) {
        if (indexName.compareTo(newIndexName) != 0) {
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
    // TODO: maybe read settings and mapping definition from a config file
    XContentBuilder settings = XContentFactory.jsonBuilder()
        .startObject().startObject("index")
          .startObject("analysis")
            // Analyzer for case-insensitive partial search
            .startObject("analyzer")
              .startObject("ngram_analyzer")
                .field("tokenizer", "ngram_tokenizer")
                .startArray("filter").value("lowercase").endArray()
              .endObject()
            .endObject()
            // n-gram tokenizer
            .startObject("tokenizer")
              .startObject("ngram_tokenizer")
                .field("type").value("nGram")
                .field("min_gram").value("1")
                .field("max_gram").value("20")
              .endObject()
            .endObject()
            // Analyzer for case-insensitive resource sorting
            .startObject("analyzer")
              .startObject("sortable")
                .field("tokenizer", "keyword")
                .startArray("filter").value("lowercase").endArray()
              .endObject()
            .endObject()
        .endObject()
        .endObject().endObject();
    // Mapping definition for search index. The info.@id field is set as not analyzed
    XContentBuilder mapping = XContentFactory.jsonBuilder()
        .startObject()
          .startObject(documentType)
            .startObject("properties")
              .startObject("templateId")
                .field("type", "string")
                .field("index", "not_analyzed")
              .endObject()
              .startObject("info")
                .startObject("properties")
                  .startObject("@id")
                    .field("type", "string")
                    .field("index", "not_analyzed")
                  .endObject()
                .startObject("ownedBy")
                  .field("type", "string")
                  .field("index", "not_analyzed")
                .endObject()
                // Name field
                .startObject("name")
                  .field("type", "string")
                  .field("analyzer", "ngram_analyzer")
                  .field("search_analyzer", "standard")
                  .startObject("fields")
                      // Apply the sortable analyzer to the raw field
                    .startObject("raw")
                      .field("type", "string")
                      .field("analyzer", "sortable")
                    .endObject()
                  .endObject()
                .endObject()
              .endObject()
            .endObject()
          .endObject()
        .endObject().endObject();
    esService.createIndex(indexName, documentType, settings, mapping);
  }

  private CedarRSNode setResourceDetails(CedarRSNode resource) {
    resource.setDisplayName(resource.getName());
    return resource;
  }

}

