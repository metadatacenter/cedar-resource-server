package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.response.RSNodeListResponse;

public interface ISearchService {

  void addToIndex(CedarIndexResource resource);
  void removeFromIndex(String resourceId);
  // TODO: Update resource on index
  RSNodeListResponse search(String query);

}
