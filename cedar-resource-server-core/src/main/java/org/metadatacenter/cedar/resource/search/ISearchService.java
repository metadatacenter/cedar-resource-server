package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.response.RSNodeListResponse;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

public interface ISearchService {

  void addToIndex(CedarIndexResource resource) throws UnknownHostException;;
  void removeFromIndex(String resourceId);
  // TODO: Update resource on index
  RSNodeListResponse search(String query, List<String> resourceTypes) throws IOException;

}
