package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.EncoderException;
import org.metadatacenter.model.resourceserver.ResourceServerNode;
import org.metadatacenter.model.response.ResourceServerNodeListResponse;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.AuthRequest;

import java.io.IOException;
import java.util.List;

public interface ISearchService {

  void indexResource(ResourceServerNode resource, JsonNode resourceContent, String indexName, String documentType, AuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  void indexResource(ResourceServerNode resource, JsonNode resourceContent, AuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws IOException;
  void removeResourceFromIndex(String resourceId) throws IOException;
  void updateIndexedResource(ResourceServerNode newResource, JsonNode resourceContent, String indexName, String documentType, AuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  void updateIndexedResource(ResourceServerNode newResource, JsonNode resourceContent, AuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  ResourceServerNodeListResponse search(String query, List<String> resourceTypes, String templateId, List<String> sortList, int limit, int offset, String absoluteUrl, AuthRequest authRequest) throws IOException;
  ResourceServerNodeListResponse searchDeep(String query, List<String> resourceTypes, String templateId, List<String> sortList, int limit) throws IOException;
  void regenerateSearchIndex(boolean force, AuthRequest authRequest) throws IOException, CedarAccessException, EncoderException, InterruptedException;

}
