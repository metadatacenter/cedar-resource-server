package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only model for the parameters of an attach/detach category operation.
 *
 * <p>The attach/detach category command body is read as raw JSON with no fixed Java type on the
 * wire, so this thin bean exists purely to reproduce the {@code CategoryAttachRequest} schema that
 * the hand-authored spec exposed. It mirrors that schema exactly.</p>
 */
@Schema(name = "CategoryAttachRequest", description = "Parameters of the attach/detach operation.")
public class CategoryAttachRequest {

  @Schema(name = "artifactId", description = "Unique URL identifier representing the artifact.")
  private String artifactId;

  @Schema(name = "categoryId", description = "Unique URL identifier representing the category.")
  private String categoryId;

  public String getArtifactId() {
    return artifactId;
  }

  public void setArtifactId(String artifactId) {
    this.artifactId = artifactId;
  }

  public String getCategoryId() {
    return categoryId;
  }

  public void setCategoryId(String categoryId) {
    this.categoryId = categoryId;
  }
}
