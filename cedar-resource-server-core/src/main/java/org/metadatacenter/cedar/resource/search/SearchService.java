package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.util.FolderServerUtil;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.FolderOrResource;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.PermissionServiceSession;
import org.metadatacenter.server.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.server.search.elasticsearch.IndexedDocumentId;
import org.metadatacenter.server.search.permission.PermissionSearchService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.auth.CedarNodeMaterializedPermissions;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_ID_FIELD;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_PREFIX;

public class SearchService implements ISearchService {

  private static final Logger log = LoggerFactory.getLogger(SearchService.class);

  private CedarConfig cedarConfig;
  private ElasticsearchService esService;
  private String esIndex;
  private String esTypeResource;
  private String esTypePermissions;
  private IndexUtils indexUtils;
  private PermissionSearchService permissionSearchService;

  // TODO: folderBase, templateBase, limit, maxAttempts and delayAttempts should ideally come from a centralized
  // configuration solution instead of passing them to the constructor. IndexUtils should be able to directly read
  // those parameters and it would not be necessary to create an instance of it. Static methods could be used instead.
  public SearchService(CedarConfig cedarConfig, ElasticsearchService esService,
                       PermissionSearchService permissionSearchService,
                       String esIndex, String esTypeResource, String esTypePermissions) {
    this.cedarConfig = cedarConfig;
    this.esService = esService;
    this.permissionSearchService = permissionSearchService;
    this.esIndex = esIndex;
    this.esTypeResource = esTypeResource;
    this.esTypePermissions = esTypePermissions;

    String folderBase = cedarConfig.getServers().getFolder().getBase();
    String templateBase = cedarConfig.getServers().getTemplate().getBase();
    int limit = cedarConfig.getSearchSettings().getSearchRetrieveSettings().getLimitIndexRegeneration();
    int maxAttempts = cedarConfig.getSearchSettings().getSearchRetrieveSettings().getMaxAttempts();
    int delayAttempts = cedarConfig.getSearchSettings().getSearchRetrieveSettings().getDelayAttempts();

    this.indexUtils = new IndexUtils(folderBase, templateBase, limit, maxAttempts, delayAttempts);
  }

  public IndexedDocumentId indexResource(FolderServerNode resource, JsonNode resourceContent, String indexName, String
      documentType, CedarRequestContext context) throws CedarProcessingException {
    JsonNode summarizedContent = null;
    if (resourceContent != null) {
      summarizedContent = indexUtils.extractSummarizedContent(resource.getType(), resourceContent, context);
    }
    log.debug("Indexing resource (id = " + resource.getId() + ")");
    // Set resource details
    String templateId = null;
    if (CedarNodeType.INSTANCE.equals(resource.getType())) {
      templateId = resourceContent.get("schema:isBasedOn").asText();
    }
    resource = setResourceDetails(resource);
    CedarIndexResource ir = new CedarIndexResource(resource, summarizedContent, templateId);
    JsonNode jsonResource = new ObjectMapper().convertValue(ir, JsonNode.class);
    return esService.addToIndex(jsonResource, indexName, documentType, null);
  }

  public IndexedDocumentId indexResource(FolderServerNode resource, JsonNode resourceContent, CedarRequestContext context) throws
      CedarProcessingException {
    // Use default index and type
    return indexResource(resource, resourceContent, esIndex, esTypeResource, context);
  }

  public void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws
      CedarProcessingException {
    log.debug("Removing resource from index (id = " + resourceId);
    esService.removeFromIndex(resourceId, indexName, documentType);
  }

  public void removeResourceFromIndex(String resourceId) throws CedarProcessingException {
    // Use default index and type
    removeResourceFromIndex(resourceId, esIndex, esTypeResource);
  }


  public IndexedDocumentId updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, String indexName, String
      documentType, CedarRequestContext context) throws CedarProcessingException {
    JsonNode summarizedContent = null;
    try {
      if (resourceContent != null) {
        summarizedContent = indexUtils.extractSummarizedContent(newResource.getType(), resourceContent, context);
      }
      log.debug("Updating resource (id = " + newResource.getId());
      removeResourceFromIndex(newResource.getId(), indexName, documentType);
      String templateId = null;
      if (CedarNodeType.INSTANCE.equals(newResource.getType())) {
        templateId = resourceContent.get("schema:isBasedOn").asText();
      }
      newResource = setResourceDetails(newResource);
      return addToIndex(new CedarIndexResource(newResource, summarizedContent, templateId), indexName, documentType, null);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  public IndexedDocumentId updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, CedarRequestContext context)
      throws CedarProcessingException {
    // Use default index and type
    return updateIndexedResource(newResource, resourceContent, esIndex, esTypeResource, context);
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
        SearchResponse esResults = esService.search(query, resourceTypes, sortList, templateId, esIndex, esTypeResource, limit,
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
        esResults = esService.search(query, resourceTypes, sortList, templateId, esIndex, esTypeResource, limit, offsetIndex);
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
      List<SearchHit> esHits = esService.searchDeep(query, resourceTypes, sortList, templateId, esIndex, esTypeResource, limit);

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
    try {
      PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(context);
      // Get all resources
      List<FolderServerNode> resources = indexUtils.findAllResources(context);
      // Checks if is necessary to regenerate the index or not
      if (!force) {
        log.info("Checking if it is necessary to regenerate the search index from DB");
        // Check if the index exists (using the alias). If it exists, check if it contains all resources
        if (esService.indexExists(esIndex)) {
          // Use the resource ids to check if the resources in the DBs and in the index are different
          List<String> dbResourceIds = getResourceIds(resources);
          log.info("No. of resources in DB that are expected to be indexed: " + dbResourceIds.size());
          List<String> indexResourceIds = esService.findAllValuesForField(ES_RESOURCE_PREFIX + ES_RESOURCE_ID_FIELD,
              esIndex, esTypeResource);
          log.info("No. of resources in the index: " + indexResourceIds.size());
          if (dbResourceIds.size() == indexResourceIds.size()) {
            // Compare the two lists
            List<String> tmp1 = new ArrayList(dbResourceIds);
            List<String> tmp2 = new ArrayList(indexResourceIds);
            Collections.sort(tmp1);
            Collections.sort(tmp2);
            if (tmp1.equals(tmp2)) {
              regenerate = false;
              log.info("DB and search index match. It is not necessary to regenerate the index");
            } else {
              log.warn("DB and search index do not match!");
            }
          } else {
            log.warn("DB and search index do not match! (different size)");
          }
        } else {
          log.warn("The search index '" + esIndex + "' does not exist!");
        }
      }
      if (regenerate) {
        log.info("Regenerating index");
        // Create new index and set it up
        String newIndexName = indexUtils.getNewIndexName(esIndex);
        esService.createIndex(newIndexName, esTypeResource, esTypePermissions);
        // Get resources content and index it
        int count = 1;
        for (FolderServerNode resource : resources) {
          CedarNodeMaterializedPermissions perm = null;
          IndexedDocumentId parent = null;
          if (resource.getType() == CedarNodeType.FOLDER) {
            parent = indexResource(resource, null, newIndexName, esTypeResource, context);
            perm = permissionSession.getNodeMaterializedPermission(resource.getId(), FolderOrResource.FOLDER);
          } else {
            JsonNode resourceContent = indexUtils.findResourceContent(resource.getId(), resource.getType(), context);
            if (resourceContent != null) {
              parent = indexResource(resource, resourceContent, newIndexName, esTypeResource, context);
              perm = permissionSession.getNodeMaterializedPermission(resource.getId(), FolderOrResource.RESOURCE);
            }
          }
          if (perm != null) {
            permissionSearchService.indexResource(perm, newIndexName, parent);
          } else {
            log.error("Permissions not found for " + resource.getType() + ":" + resource.getId());
          }
          float progress = (100 * count++) / resources.size();
          log.info(String.format("Progress: %.0f%%", progress));
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
    } catch (Exception e) {
      log.error("Error while regenerating index", e);
      throw new CedarProcessingException(e);
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

  private IndexedDocumentId addToIndex(CedarIndexResource resource, String indexName, String documentType, IndexedDocumentId parent) throws
      CedarProcessingException {
    JsonNode resourceJson = new ObjectMapper().valueToTree(resource);
    return esService.addToIndex(resourceJson, indexName, documentType, parent);
  }


  private FolderServerNode setResourceDetails(FolderServerNode resource) {
    resource.setDisplayName(resource.getName());
    return resource;
  }

  public void shutdown() {
    esService.closeClient();
  }

}

