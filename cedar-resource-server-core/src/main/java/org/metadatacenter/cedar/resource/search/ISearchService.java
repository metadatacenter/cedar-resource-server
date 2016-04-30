package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.response.RSNodeListResponse;

import java.util.List;

public interface ISearchService {

  void addToIndex(CedarIndexResource resource) throws Exception;;
  void removeFromIndex(String resourceId) throws Exception;
  RSNodeListResponse search(String query, List<String> resourceTypes) throws Exception;

}
