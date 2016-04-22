package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.cedar.resource.model.MyResourceListResponse;

public interface ISearchService {

  MyResourceListResponse search(String query);

}
