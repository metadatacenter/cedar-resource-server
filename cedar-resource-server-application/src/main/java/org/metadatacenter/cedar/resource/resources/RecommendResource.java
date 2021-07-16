package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.request.TemplateRecommendationRequest;
import org.metadatacenter.model.request.TemplateRecommendationRequestSummary;
import org.metadatacenter.model.response.ResourceRecommendation;
import org.metadatacenter.model.response.TemplateRecommendationResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.search.IndexedDocumentDocument;
import org.metadatacenter.search.InfoField;
import org.metadatacenter.server.search.elasticsearch.worker.SearchResponseResult;
import org.metadatacenter.util.StringUtil;
import org.metadatacenter.util.http.PagedSortedTypedSearchQuery;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.*;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class RecommendResource extends AbstractSearchResource {

  public RecommendResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/recommend-templates")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response recommendTemplates() throws CedarException, IOException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    TemplateRecommendationRequest recommendationRequest =
        JsonMapper.MAPPER.readValue(c.request().getRequestBody().asJsonString(), TemplateRecommendationRequest.class);

    Iterator<String> itFieldNames = recommendationRequest.getMetadataRecord().fieldNames();
    List<String> fieldNames = new ArrayList<>();
    while (itFieldNames.hasNext()) {
      fieldNames.add(itFieldNames.next());
    }

    // 1: Basic search to retrieve templates by field name
    SearchResponseResult searchResponse = searchArtifactsByFieldNames(c, fieldNames, CedarResourceType.Types.TEMPLATE);

    // 2. Generation of recommendations
    int sourceFieldsCount = fieldNames.size();
    Set<String> fieldNamesNormalizedSet = new HashSet<>(); // To find by field name in O(1)
    for (String fieldName : fieldNames) {
      fieldNamesNormalizedSet.add(StringUtil.basicNormalization(fieldName));
    }
    List<ResourceRecommendation> recommendations = new ArrayList<>();
    int totalCount = Math.min(cedarConfig.getResourceRESTAPI().getPagination().getDefaultPageSize(),
        (int) searchResponse.getTotalCount());

    for (SearchHit hit : searchResponse.getHits().subList(0, totalCount)) {
      IndexedDocumentDocument indexedDoc = JsonMapper.MAPPER.readValue(hit.getSourceAsString(),
          IndexedDocumentDocument.class);
      int sourceFieldsMatched = 0;
      for (InfoField targetField : indexedDoc.getInfoFields()) {
        String normFieldName = StringUtil.basicNormalization(targetField.getFieldName());
        String normFieldPrefLabel = StringUtil.basicNormalization(targetField.getFieldPrefLabel());
        if (fieldNamesNormalizedSet.contains(normFieldName) || fieldNamesNormalizedSet.contains(normFieldPrefLabel)) {
          sourceFieldsMatched++;
        }
      }
      int targetFieldsCount = indexedDoc.getInfoFields().size();
      // Score calculated using the Jaccard Index
      double recommendationScore =
          (double) sourceFieldsMatched / (double) (sourceFieldsCount + targetFieldsCount - sourceFieldsMatched);
      IndexedDocumentDocument indexedDocument = JsonMapper.MAPPER.readValue(hit.getSourceAsString(),
          IndexedDocumentDocument.class);
      FolderServerResourceExtract resourceExtract = FolderServerResourceExtract.fromNodeInfo(indexedDocument.getInfo());
      ResourceRecommendation recommendation = new ResourceRecommendation(recommendationScore, sourceFieldsMatched,
          targetFieldsCount, resourceExtract);
      recommendations.add(recommendation);
    }

    // 3. Rank recommendations by recommendation score
    Collections.sort(recommendations);

    // 4. Assemble and return response
    TemplateRecommendationResponse recommendationResponse = new TemplateRecommendationResponse();
    recommendationResponse.setTotalCount(totalCount);
    recommendationResponse.setRequest(new TemplateRecommendationRequestSummary(sourceFieldsCount));
    recommendationResponse.setRecommendations(recommendations);
    return Response.ok().entity(recommendationResponse).build();
  }

  private SearchResponseResult searchArtifactsByFieldNames(CedarRequestContext ctx, List<String> fieldNames,
                                                           String resourceTypes) throws CedarException {

    StringBuilder queryBuilder = new StringBuilder();
    for (int i = 0; i < fieldNames.size() - 1; i++) {
      queryBuilder.append("\"").append(fieldNames.get(i)).append("\"").append(": OR ");
    }
    queryBuilder.append("\"").append(fieldNames.get(fieldNames.size() - 1)).append("\"").append(":");

    PagedSortedTypedSearchQuery pagedSearchQuery = new PagedSortedTypedSearchQuery(
        cedarConfig.getResourceRESTAPI().getPagination())
        .q(Optional.of(queryBuilder.toString()))
        .id(Optional.empty())
        .resourceTypes(Optional.of(resourceTypes))
        .version(Optional.empty())
        .publicationStatus(Optional.empty())
        .isBasedOn(Optional.empty())
        .categoryId(Optional.empty())
        .mode(Optional.empty())
        .sort(Optional.empty())
        .limit(Optional.empty())
        .offset(Optional.empty());
    pagedSearchQuery.validate();

    SearchResponseResult result = nodeSearchingService.search(ctx, pagedSearchQuery.getQ(),
        pagedSearchQuery.getResourceTypeAsStringList(),
        pagedSearchQuery.getVersion(), pagedSearchQuery.getPublicationStatus(), pagedSearchQuery.getCategoryId(),
        pagedSearchQuery.getSortList(), pagedSearchQuery.getLimit(),
        pagedSearchQuery.getOffset());

    return result;

  }

}

