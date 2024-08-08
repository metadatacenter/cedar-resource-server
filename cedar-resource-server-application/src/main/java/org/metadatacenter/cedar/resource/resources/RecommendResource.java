package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.opensearch.search.SearchHit;
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
import me.xdrop.fuzzywuzzy.FuzzySearch;

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

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
public class RecommendResource extends AbstractSearchResource {

  public RecommendResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  final double SIMILARITY_THRESHOLD = 0.9;

  @POST
  @Timed
  @Path("/recommend")
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
    List<ResourceRecommendation> recommendations = new ArrayList<>();
    int totalCount = Math.min(cedarConfig.getResourceRESTAPI().getPagination().getDefaultPageSize(), (int) searchResponse.getTotalCount());

    for (SearchHit hit : searchResponse.getHits().subList(0, totalCount)) {
      // For each template returned by ElasticSearch, measure the similarity between the input metadata fields and the
      // template fields, and calculate a recommendation score based on the Jaccard Index
      IndexedDocumentDocument indexedDoc = JsonMapper.MAPPER.readValue(hit.getSourceAsString(), IndexedDocumentDocument.class);
      int sourceFieldsMatched = 0;
      for (String fieldName : fieldNames) {
        for (InfoField targetField : indexedDoc.getInfoFields()) {
          if ((targetField.getFieldName() != null && calculateSimilarity(fieldName, targetField.getFieldName()) >= SIMILARITY_THRESHOLD)
              || (targetField.getFieldPrefLabel() != null && calculateSimilarity(fieldName, targetField.getFieldPrefLabel()) >= SIMILARITY_THRESHOLD)) {
            sourceFieldsMatched++;
            break;
          }
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
    recommendationResponse.setTotalCount(recommendations.size());
    recommendationResponse.setRequestSummary(new TemplateRecommendationRequestSummary(sourceFieldsCount));
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

  public static double calculateSimilarity(String str1, String str2) {
    if (str1 == null || str2 == null) {
      throw new IllegalArgumentException("Null argument");
    }
    // Apply basic normalization
    str1 = StringUtil.basicNormalization(str1);
    str2 = StringUtil.basicNormalization(str2);
    // FuzzySearch.weightedRatio calculates a weighted ratio between the different FuzzyWuzzy algorithms for best results
    int ratio = FuzzySearch.ratio(str1, str2);
    return (double) ratio / (double) 100;
  }



}

