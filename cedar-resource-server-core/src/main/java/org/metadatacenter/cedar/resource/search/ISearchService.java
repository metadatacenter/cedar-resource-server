package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.rest.context.CedarRequestContext;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

public interface ISearchService {

  void indexResource(FolderServerNode resource, JsonNode resourceContent, String indexName, String documentType,
                     CedarRequestContext context) throws CedarProcessingException;

  void indexResource(FolderServerNode resource, JsonNode resourceContent, CedarRequestContext context) throws
      CedarProcessingException;

  void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws
      CedarProcessingException;

  void removeResourceFromIndex(String resourceId) throws CedarProcessingException;

  void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, String indexName, String
      documentType, CedarRequestContext context) throws CedarProcessingException;

  void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, CedarRequestContext context) throws
      CedarProcessingException;

  FolderServerNodeListResponse search(String query, List<String> resourceTypes, String templateId, List<String>
      sortList, int limit, int offset, String absoluteUrl, CedarRequestContext context) throws CedarProcessingException;

  FolderServerNodeListResponse searchDeep(String query, List<String> resourceTypes, String templateId, List<String>
      sortList, int limit) throws CedarProcessingException;

  void regenerateSearchIndex(boolean force, CedarRequestContext context) throws CedarProcessingException;

  void shutdown();

}
