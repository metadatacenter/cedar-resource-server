package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.core.JsonParseException;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.response.RSNodeListResponse;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

public interface ISearchService {

  void initializeSearchIndex() throws IOException;
  void addToIndex(CedarIndexResource resource) throws IOException;
  void removeFromIndex(String resourceId) throws IOException;
  RSNodeListResponse search(String query, List<String> resourceTypes) throws IOException;
  void regenerateSearchIndex(List<CedarIndexResource> resources, boolean force) throws IOException;
}
