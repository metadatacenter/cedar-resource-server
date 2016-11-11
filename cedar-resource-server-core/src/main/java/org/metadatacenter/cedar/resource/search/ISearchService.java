package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.EncoderException;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.AuthRequest;

import java.io.IOException;
import java.util.List;

public interface ISearchService {

  void indexResource(FolderServerNode resource, JsonNode resourceContent, String indexName, String documentType,
                     AuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;

  void indexResource(FolderServerNode resource, JsonNode resourceContent, AuthRequest authRequest) throws
      IOException, CedarAccessException, EncoderException;

  void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws IOException;

  void removeResourceFromIndex(String resourceId) throws IOException;

  void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, String indexName, String
      documentType, AuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;

  void updateIndexedResource(FolderServerNode newResource, JsonNode resourceContent, AuthRequest authRequest) throws
      IOException, CedarAccessException, EncoderException;

  FolderServerNodeListResponse search(String query, List<String> resourceTypes, String templateId, List<String>
      sortList, int limit, int offset, String absoluteUrl, AuthRequest authRequest) throws IOException;

  FolderServerNodeListResponse searchDeep(String query, List<String> resourceTypes, String templateId, List<String>
      sortList, int limit) throws IOException;

  void regenerateSearchIndex(boolean force, AuthRequest authRequest) throws IOException, CedarAccessException,
      EncoderException, InterruptedException;

  void shutdown();

}
