package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.constant.CedarConstants;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.*;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.provenance.ProvenanceTime;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SearchService implements ISearchService {

  private ElasticsearchService esService;

  public SearchService(ElasticsearchService esService) {
    this.esService = esService;
  }

  public void addToIndex(CedarIndexResource resource) throws UnknownHostException {
    JsonNode jsonResource = new ObjectMapper().convertValue(resource, JsonNode.class);
    esService.addToIndex(jsonResource);
  }

  public void removeFromIndex(String resourceId) {

  }

  public RSNodeListResponse search(String query, List<String> resourceTypes) throws IOException {
    if (query !=null && query.compareTo("dummy")==0) {
      return getDummySearchResults();
    }
    else {
      ObjectMapper mapper = new ObjectMapper();
      SearchResponse esResults = esService.search(query, resourceTypes);
      RSNodeListResponse response = new RSNodeListResponse();
      List<CedarRSNode> resources = new ArrayList<>();
      for (SearchHit hit : esResults.getHits()) {
        String hitJson = hit.sourceAsString();
        CedarIndexResource resource = mapper.readValue(hitJson, CedarIndexResource.class);
        resources.add(resource.getInfo());
      }
      response.setTotalCount(esResults.getHits().getTotalHits());
      response.setResources(resources);
      return response;
    }
  }

  private RSNodeListResponse getDummySearchResults() {
    List<CedarRSNode> resources = new ArrayList<>();

    Instant now = Instant.now();
    String nowString = CedarConstants.xsdDateTimeFormatter.format(now);
    String nowTSString = String.valueOf(now.getEpochSecond());

    // Templates
    CedarRSResource t1 = new CedarRSTemplate();
    t1.setName("Template1");
    t1.setDescription("Template1 description");
    t1.setId("https://repo.metadatacenter.orgx/templates/" + java.util.UUID.randomUUID());
    t1.setCreatedOn(new ProvenanceTime(Date.from(now)));
    t1.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    t1.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    t1.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(t1);

    CedarRSResource t2 = new CedarRSTemplate();
    t2.setName("Template2");
    t2.setDescription("Template2 description");
    t2.setId("https://repo.metadatacenter.orgx/templates/" + java.util.UUID.randomUUID());
    t2.setCreatedOn(new ProvenanceTime(Date.from(now)));
    t2.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    t2.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    t2.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(t2);

    // Elements
    CedarRSResource e1 = new CedarRSElement();
    e1.setName("Element1");
    e1.setDescription("Element1 description");
    e1.setId("https://repo.metadatacenter.orgx/template-elements/" + java.util.UUID.randomUUID());
    e1.setCreatedOn(new ProvenanceTime(Date.from(now)));
    e1.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    e1.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    e1.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(e1);

    CedarRSResource e2 = new CedarRSElement();
    e2.setName("Element2");
    e2.setDescription("Element2 description");
    e2.setId("https://repo.metadatacenter.orgx/template-elements/" + java.util.UUID.randomUUID());
    e2.setCreatedOn(new ProvenanceTime(Date.from(now)));
    e2.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    e2.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    e2.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(e2);

    // Fields
    CedarRSResource f1 = new CedarRSField();
    f1.setName("Field1");
    f1.setDescription("Field1 description");
    f1.setId("https://repo.metadatacenter.orgx/template-fields/" + java.util.UUID.randomUUID());
    f1.setCreatedOn(new ProvenanceTime(Date.from(now)));
    f1.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    f1.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    f1.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(f1);

    CedarRSResource f2 = new CedarRSField();
    f2.setName("Field2");
    f2.setDescription("Field2 description");
    f2.setId("https://repo.metadatacenter.orgx/template-fields/" + java.util.UUID.randomUUID());
    f2.setCreatedOn(new ProvenanceTime(Date.from(now)));
    f2.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    f2.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    f2.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(f2);

    // Folders
    CedarRSNode fo1 = new CedarRSFolder();
    fo1.setName("Folder1");
    fo1.setDescription("Folder1 description");
    fo1.setId("https://repo.metadatacenter.orgx/folders/" + java.util.UUID.randomUUID());
    fo1.setCreatedOn(new ProvenanceTime(Date.from(now)));
    fo1.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    fo1.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    fo1.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(fo1);

    CedarRSNode fo2 = new CedarRSFolder();
    fo2.setName("Folder2");
    fo2.setDescription("Folder2 description");
    fo2.setId("https://repo.metadatacenter.orgx/folders/" + java.util.UUID.randomUUID());
    fo2.setCreatedOn(new ProvenanceTime(Date.from(now)));
    fo2.setLastUpdatedOn(new ProvenanceTime(Date.from(now)));
    fo2.setCreatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    fo2.setLastUpdatedBy("https://user.metadatacenter.net/users/cd36dea6-9222-412b-99ab-bec50f6ecd00");
    resources.add(fo2);

    RSNodeListResponse results = new RSNodeListResponse();
    results.setResources(resources);

    return results;
  }

}
