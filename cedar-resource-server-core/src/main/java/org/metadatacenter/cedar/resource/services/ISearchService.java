package org.metadatacenter.cedar.resource.services;

import org.metadatacenter.cedar.resource.customObjects.SearchResults;

public interface ISearchService {

  SearchResults search(String query);

}
