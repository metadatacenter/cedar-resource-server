package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.EncoderException;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;

import java.io.IOException;
import java.util.List;

public interface ISearchService {

  void indexResource(CedarRSNode resource, JsonNode resourceContent, String indexName, String documentType, IAuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  void indexResource(CedarRSNode resource, JsonNode resourceContent, IAuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws IOException;
  void removeResourceFromIndex(String resourceId) throws IOException;
  void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent, String indexName, String documentType, IAuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent, IAuthRequest authRequest) throws IOException, CedarAccessException, EncoderException;
  RSNodeListResponse search(String query, List<String> resourceTypes, List<String> sortList) throws IOException;
  void regenerateSearchIndex(boolean force, IAuthRequest authRequest) throws IOException, CedarAccessException, EncoderException, InterruptedException;

}
