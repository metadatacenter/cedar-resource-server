package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.EncoderException;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.exception.CedarProcessingException;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.AuthRequest;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;

public interface ISearchService {

  void indexResource(FolderServerNode resource, JsonNode resourceContent, String indexName, String documentType,
                     HttpServletRequest request) throws CedarProcessingException;

  void indexResource(FolderServerNode resource, JsonNode resourceContent, HttpServletRequest request) throws
      CedarProcessingException;

  void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws
      CedarProcessingException;

  void removeResourceFromIndex(String resourceId) throws CedarProcessingException;

  void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, String indexName, String
      documentType, HttpServletRequest request) throws CedarProcessingException;

  void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, HttpServletRequest request) throws
      CedarProcessingException;

  FolderServerNodeListResponse search(String query, List<String> resourceTypes, String templateId, List<String>
      sortList, int limit, int offset, String absoluteUrl, HttpServletRequest request) throws CedarProcessingException;

  FolderServerNodeListResponse searchDeep(String query, List<String> resourceTypes, String templateId, List<String>
      sortList, int limit) throws CedarProcessingException;

  void regenerateSearchIndex(boolean force, HttpServletRequest request) throws CedarProcessingException;

  void shutdown();

}
