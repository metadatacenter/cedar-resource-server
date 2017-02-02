package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.cedar.resource.util.FolderServerUtil;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.util.http.LinkHeaderUtil;

import java.util.*;

import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_ID_FIELD;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_PREFIX;

public class SearchService implements ISearchService {

  private CedarConfig cedarConfig;
  private ElasticsearchService esService;
  private String esIndex;
  private String esType;
  private IndexUtils indexUtils;

  // TODO: folderBase, templateBase, limit, maxAttempts and delayAttempts should ideally come from a centralized
  // configuration solution instead of passing them to the constructor. IndexUtils should be able to directly read
  // those parameters and it would not be necessary to create an instance of it. Static methods could be used instead.
  public SearchService(CedarConfig cedarConfig, ElasticsearchService esService, String esIndex, String esType) {
    this.cedarConfig = cedarConfig;
    this.esService = esService;
    this.esIndex = esIndex;
    this.esType = esType;

    String folderBase = cedarConfig.getServers().getFolder().getBase();
    String templateBase = cedarConfig.getServers().getTemplate().getBase();
    int limit = cedarConfig.getSearchSettings().getSearchRetrieveSettings().getLimitIndexRegeneration();
    int maxAttempts = cedarConfig.getSearchSettings().getSearchRetrieveSettings().getMaxAttempts();
    int delayAttempts = cedarConfig.getSearchSettings().getSearchRetrieveSettings().getDelayAttempts();

    this.indexUtils = new IndexUtils(folderBase, templateBase, limit, maxAttempts, delayAttempts);
  }

  public void indexResource(FolderServerNode resource, JsonNode resourceContent, String indexName, String
      documentType, CedarRequestContext context) throws CedarProcessingException {
    JsonNode summarizedContent = null;
    if (resourceContent != null) {
      summarizedContent = indexUtils.extractSummarizedContent(resource.getType(), resourceContent, context);
    }
    System.out.println("Indexing resource (id = " + resource.getId() + ")");
    // Set resource details
    String templateId = null;
    if (CedarNodeType.INSTANCE.equals(resource.getType())) {
      templateId = resourceContent.get("schema:isBasedOn").asText();
    }
    resource = setResourceDetails(resource);
    CedarIndexResource ir = new CedarIndexResource(resource, summarizedContent, templateId);
    JsonNode jsonResource = new ObjectMapper().convertValue(ir, JsonNode.class);
    esService.addToIndex(jsonResource, indexName, documentType);
  }

  public void indexResource(FolderServerNode resource, JsonNode resourceContent, CedarRequestContext context) throws
      CedarProcessingException {
    // Use default index and type
    indexResource(resource, resourceContent, esIndex, esType, context);
  }

  public void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws
      CedarProcessingException {
    System.out.println("Removing resource from index (id = " + resourceId);
    esService.removeFromIndex(resourceId, indexName, documentType);
  }

  public void removeResourceFromIndex(String resourceId) throws CedarProcessingException {
    // Use default index and type
    removeResourceFromIndex(resourceId, esIndex, esType);
  }


  public void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, String indexName, String
      documentType, CedarRequestContext context) throws CedarProcessingException {
    JsonNode summarizedContent = null;
    try {
      if (resourceContent != null) {
        summarizedContent = indexUtils.extractSummarizedContent(newResource.getType(), resourceContent, context);
      }
      System.out.println("Updating resource (id = " + newResource.getId());
      removeResourceFromIndex(newResource.getId(), indexName, documentType);
      String templateId = null;
      if (CedarNodeType.INSTANCE.equals(newResource.getType())) {
        templateId = resourceContent.get("schema:isBasedOn").asText();
      }
      newResource = setResourceDetails(newResource);
      addToIndex(new CedarIndexResource(newResource, summarizedContent, templateId), indexName, documentType);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  public void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, CedarRequestContext context)
      throws CedarProcessingException {
    // Use default index and type
    updateIndexedResource(newResource, resourceContent, esIndex, esType, context);
  }

  public FolderServerNodeListResponse search(String query, List<String> resourceTypes, String templateId, List<String>
      sortList, int limit, int offset, String absoluteUrl, CedarRequestContext context) throws
      CedarProcessingException {
    ObjectMapper mapper = new ObjectMapper();

    // Accessible node ids
    HashMap<String, String> accessibleNodeIds = new HashMap(FolderServerUtil.getAccessibleNodeIds(cedarConfig,
        context));

    try {

      // The offset for the resources accessible by the user needs to be translated to the offset at the index level
      int offsetIndex = 0;
      int count = 0;
      while (count < offset) {
        SearchResponse esResults = esService.search(query, resourceTypes, sortList, templateId, esIndex, esType, limit,
            offsetIndex);
        if (esResults.getHits().getHits().length == 0) {
          break;
        }
        for (SearchHit hit : esResults.getHits()) {
          String hitJson = hit.sourceAsString();
          CedarIndexResource resource = mapper.readValue(hitJson, CedarIndexResource.class);
          // The resource is taken into account only if the user has access to it
          if (accessibleNodeIds.containsKey(resource.getInfo().getId())) {
            count++;
          }
          offsetIndex++;
          if (count == offset) {
            break;
          }
        }
      }

      // Retrieve resources
      FolderServerNodeListResponse response = new FolderServerNodeListResponse();
      List<FolderServerNode> resources = new ArrayList<>();
      SearchResponse esResults = null;
      while (resources.size() < limit) {
        esResults = esService.search(query, resourceTypes, sortList, templateId, esIndex, esType, limit, offsetIndex);
        if (esResults.getHits().getHits().length == 0) {
          break;
        }
        for (SearchHit hit : esResults.getHits()) {
          String hitJson = hit.sourceAsString();
          CedarIndexResource resource = mapper.readValue(hitJson, CedarIndexResource.class);
          // The resource is added to the results only if the user has access to it
          if (accessibleNodeIds.containsKey(resource.getInfo().getId())) {
            resources.add(resource.getInfo());
            if (resources.size() == limit) {
              break;
            }
          }
          offsetIndex++;
        }
      }

      // The total number of resources accessible by the user is sometimes too expensive to compute with our current
      // (temporal)
      // approach, so we set the total to -1 unless offsetIndex = totalHits, which means that there are no more
      // resources
      // in the index that match the query and that we can be sure about the number of resources that the user has
      // access to.
      long total = -1;
      if (offsetIndex == esResults.getHits().getTotalHits()) {
        total = resources.size();
      }
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
      req.setQ(query);
      req.setDerivedFromId(templateId);
      response.setRequest(req);

      return response;
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  // TODO: Update to take into account the user's access permissions to resources
  public FolderServerNodeListResponse searchDeep(String query, List<String> resourceTypes, String templateId,
                                                 List<String> sortList, int limit) throws CedarProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    try {
      List<SearchHit> esHits = esService.searchDeep(query, resourceTypes, sortList, templateId, esIndex, esType, limit);

      // Apply limit
      if (esHits.size() >= limit) {
        esHits = esHits.subList(0, limit);
      }

      FolderServerNodeListResponse response = new FolderServerNodeListResponse();
      List<FolderServerNode> resources = new ArrayList<>();
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
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  public void regenerateSearchIndex(boolean force, CedarRequestContext context) throws CedarProcessingException {
    boolean regenerate = true;
    // Get all resources
    List<FolderServerNode> resources = indexUtils.findAllResources(context);
    // Checks if is necessary to regenerate the index or not
    if (!force) {
      System.out.println("Checking if it is necessary to regenerate the search index from DB");
      // Check if the index exists (using the alias). If it exists, check if it contains all resources
      if (esService.indexExists(esIndex)) {
        // Use the resource ids to check if the resources in the DBs and in the index are different
        List<String> dbResourceIds = getResourceIds(resources);
        System.out.println("No. of resources in DB that are expected to be indexed: " + dbResourceIds.size());
        List<String> indexResourceIds = esService.findAllValuesForField(ES_RESOURCE_PREFIX + ES_RESOURCE_ID_FIELD,
            esIndex, esType);
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
      // Create new index and set it up
      String newIndexName = esIndex + "-" + Long.toString(Calendar.getInstance().getTimeInMillis());
      esService.createIndex(newIndexName, esType);
      // Get resources content and index it
      int count = 1;
      for (FolderServerNode resource : resources) {
        if (resource.getType() != CedarNodeType.FOLDER) {
          JsonNode resourceContent = indexUtils.findResourceContent(resource.getId(), resource.getType(), context);
          if (resourceContent != null) {
            indexResource(resource, resourceContent, newIndexName, esType, context);
          }
        } else {
          indexResource(resource, null, newIndexName, esType, context);
        }
        float progress = (100 * count++) / resources.size();
        System.out.println(String.format("Progress: %.0f%%", progress));
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

  private List<String> getResourceIds(List<FolderServerNode> resources) {
    List<String> ids = new ArrayList<>();
    for (FolderServerNode resource : resources) {
      ids.add(resource.getId());
    }
    return ids;
  }

  private void addToIndex(CedarIndexResource resource, String indexName, String documentType) throws
      CedarProcessingException {
    JsonNode resourceJson = new ObjectMapper().valueToTree(resource);
    esService.addToIndex(resourceJson, indexName, documentType);
  }


  private FolderServerNode setResourceDetails(FolderServerNode resource) {
    resource.setDisplayName(resource.getName());
    return resource;
  }

  public void shutdown() {
    esService.closeClient();
  }

}

