package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.util.Map;

/**
 * Documentation-only model for a template recommendation request.
 *
 * <p>The recommend command body is read as raw JSON with no fixed Java type on the wire, so this
 * thin bean exists purely to reproduce the {@code TemplateRecommendationRequest} schema that the
 * hand-authored spec exposed. It mirrors that schema exactly: a single free-form
 * {@code metadataRecord} object (e.g. {@code {"tissue": "lung", "disease": "influenza"}}).</p>
 */
@ApiModel(value = "TemplateRecommendationRequest", description = "The metadata record to get recommendations for.")
public class TemplateRecommendationRequest {

  @ApiModelProperty(name = "metadataRecord",
      value = "The input metadata record. Example: {\"tissue\": \"lung\", \"disease\": \"influenza\"}")
  private Map<String, Object> metadataRecord;

  public Map<String, Object> getMetadataRecord() {
    return metadataRecord;
  }

  public void setMetadataRecord(Map<String, Object> metadataRecord) {
    this.metadataRecord = metadataRecord;
  }
}
