package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.EncoderException;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.exception.CedarAccessException;

import java.io.IOException;
import java.util.List;

public interface ISearchService {

  void indexResource(CedarRSNode resource, JsonNode resourceContent, String indexName, String documentType) throws IOException;
  void indexResource(CedarRSNode resource, JsonNode resourceContent) throws IOException;
  void removeResourceFromIndex(String resourceId, String indexName, String documentType) throws IOException;
  void removeResourceFromIndex(String resourceId) throws IOException;
  void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent, String indexName, String documentType) throws IOException;
  void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent) throws IOException;
  RSNodeListResponse search(String query, List<String> resourceTypes) throws IOException;
  void regenerateSearchIndex(boolean force, String apiKey) throws IOException, CedarAccessException, EncoderException, InterruptedException;

}
