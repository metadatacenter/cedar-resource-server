package org.metadatacenter.cedar.resource.services;

import org.metadatacenter.cedar.resource.customObjects.SearchResults;
import org.metadatacenter.model.resourceserver.CedarRSResource;
import org.metadatacenter.model.resourceserver.CedarRSTemplate;

import java.util.ArrayList;
import java.util.List;

public class SearchService {

  public SearchResults search(String query) {
    CedarRSResource t1 = new CedarRSTemplate();
    t1.setName("Person");
    t1.setDescription("Person template");
    t1.setId("https://repo.metadatacenter.orgx/folders/dbb73bd9-7920-4300-8d78-b2fd36472669");

    List<CedarRSResource> results = new ArrayList<>();
    results.add(t1);

    SearchResults sr = new SearchResults(1, 1, 1, 0, 0, results);

    return sr;
  }

}
