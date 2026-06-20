package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.util.List;

/**
 * Documentation-only model for the parameters of a multiple-category attach operation.
 *
 * <p>The attach-categories command body is read as raw JSON with no fixed Java type on the wire, so
 * this thin bean exists purely to reproduce the {@code CategoryAttachListRequest} schema that the
 * hand-authored spec exposed. It mirrors that schema exactly.</p>
 */
@ApiModel(value = "CategoryAttachListRequest", description = "Parameters of the attach operation.")
public class CategoryAttachListRequest {

  @ApiModelProperty(name = "artifactId", value = "Unique URL identifier representing the artifact.")
  private String artifactId;

  @ApiModelProperty(name = "categoryIds", value = "Unique URL identifier list representing the categories.")
  private List<String> categoryIds;

  public String getArtifactId() {
    return artifactId;
  }

  public void setArtifactId(String artifactId) {
    this.artifactId = artifactId;
  }

  public List<String> getCategoryIds() {
    return categoryIds;
  }

  public void setCategoryIds(List<String> categoryIds) {
    this.categoryIds = categoryIds;
  }
}
