package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.model.response.RSNodeListResponse;

public interface ISearchService {

  RSNodeListResponse search(String query);

}
