package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Documentation-only model for the parameters of a multiple-category attach operation.
 *
 * <p>The attach-categories command body is read as raw JSON with no fixed Java type on the wire, so
 * this thin bean exists purely to reproduce the {@code CategoryAttachListRequest} schema that the
 * hand-authored spec exposed. It mirrors that schema exactly.</p>
 */
@Schema(name = "CategoryAttachListRequest", description = "Parameters of the attach operation.")
public class CategoryAttachListRequest {

  @Schema(name = "artifactId", description = "Unique URL identifier representing the artifact.")
  private String artifactId;

  @Schema(name = "categoryIds", description = "Unique URL identifier list representing the categories.")
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
