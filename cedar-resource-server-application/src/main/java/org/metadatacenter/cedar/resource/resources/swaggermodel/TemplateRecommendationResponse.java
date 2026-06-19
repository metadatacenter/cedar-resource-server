package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.util.List;

/**
 * Documentation-only model for a template recommendation response.
 *
 * <p>The recommend response is serialized from internal types with no fixed documentation type on
 * the wire, so this thin bean exists purely to reproduce the {@code TemplateRecommendationResponse}
 * schema that the hand-authored spec exposed. It mirrors that schema's shape and descriptions
 * exactly via the nested {@link Request} and {@link Recommendation} models.</p>
 */
@ApiModel(value = "TemplateRecommendationResponse", description = "A ranked list of recommended templates.")
public class TemplateRecommendationResponse {

  @ApiModelProperty(name = "totalCount", value = "total number of recommendations returned", example = "1")
  private Double totalCount;

  @ApiModelProperty(name = "request")
  private Request request;

  @ApiModelProperty(name = "recommendations")
  private List<Recommendation> recommendations;

  public Double getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(Double totalCount) {
    this.totalCount = totalCount;
  }

  public Request getRequest() {
    return request;
  }

  public void setRequest(Request request) {
    this.request = request;
  }

  public List<Recommendation> getRecommendations() {
    return recommendations;
  }

  public void setRecommendations(List<Recommendation> recommendations) {
    this.recommendations = recommendations;
  }

  @ApiModel(value = "TemplateRecommendationResponseRequest")
  public static class Request {

    @ApiModelProperty(name = "sourceFieldsCount",
        value = "number of fields in the input metadata record")
    private Integer sourceFieldsCount;

    public Integer getSourceFieldsCount() {
      return sourceFieldsCount;
    }

    public void setSourceFieldsCount(Integer sourceFieldsCount) {
      this.sourceFieldsCount = sourceFieldsCount;
    }
  }

  @ApiModel(value = "TemplateRecommendation")
  public static class Recommendation {

    @ApiModelProperty(name = "recommendationScore", value = "recommendation score")
    private Double recommendationScore;

    @ApiModelProperty(name = "sourceFieldsMatched",
        value = "number of fields in the template that match fields in the input metadata record")
    private Integer sourceFieldsMatched;

    @ApiModelProperty(name = "targetFieldsCount",
        value = "total number of fields in the recommended template")
    private Integer targetFieldsCount;

    @ApiModelProperty(name = "resourceExtract")
    private Template resourceExtract;

    public Double getRecommendationScore() {
      return recommendationScore;
    }

    public void setRecommendationScore(Double recommendationScore) {
      this.recommendationScore = recommendationScore;
    }

    public Integer getSourceFieldsMatched() {
      return sourceFieldsMatched;
    }

    public void setSourceFieldsMatched(Integer sourceFieldsMatched) {
      this.sourceFieldsMatched = sourceFieldsMatched;
    }

    public Integer getTargetFieldsCount() {
      return targetFieldsCount;
    }

    public void setTargetFieldsCount(Integer targetFieldsCount) {
      this.targetFieldsCount = targetFieldsCount;
    }

    public Template getResourceExtract() {
      return resourceExtract;
    }

    public void setResourceExtract(Template resourceExtract) {
      this.resourceExtract = resourceExtract;
    }
  }
}
