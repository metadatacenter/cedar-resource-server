package org.metadatacenter.cedar.resource.open;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;

/** An ancestor name exposed for an open folder's breadcrumb, with no identifier or private metadata. */
@Schema(name = "OpenPathSegment")
public class OpenPathSegment {

  private final String name;

  private OpenPathSegment(String name) {
    this.name = name;
  }

  public static OpenPathSegment from(FolderServerResourceExtract resource) {
    return new OpenPathSegment(resource.getName());
  }

  @JsonProperty("schema:name")
  public String getName() {
    return name;
  }
}
