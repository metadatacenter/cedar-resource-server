package org.metadatacenter.cedar.resource.open;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.metadatacenter.model.BiboStatus;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ResourceVersion;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerSchemaArtifactExtract;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;

/** The complete public representation of one child in an open folder listing. */
@Schema(name = "OpenResourceSummary")
public class OpenResourceSummary {

  private final String id;
  private final CedarResourceType resourceType;
  private final String name;
  private final String version;
  private final String publicationStatus;

  private OpenResourceSummary(String id, CedarResourceType resourceType, String name, String version,
                              String publicationStatus) {
    this.id = id;
    this.resourceType = resourceType;
    this.name = name;
    this.version = version;
    this.publicationStatus = publicationStatus;
  }

  /** Fields, elements and templates also carry their version and status, which their own documents state. */
  public static OpenResourceSummary from(FolderServerResourceExtract resource) {
    String version = null;
    String publicationStatus = null;
    if (resource instanceof FolderServerSchemaArtifactExtract schemaArtifact) {
      ResourceVersion v = schemaArtifact.getVersion();
      BiboStatus s = schemaArtifact.getPublicationStatus();
      version = v == null ? null : v.getValue();
      publicationStatus = s == null ? null : s.getValue();
    }
    return new OpenResourceSummary(resource.getId(), resource.getType(), resource.getName(), version,
        publicationStatus);
  }

  @JsonProperty("@id")
  public String getId() {
    return id;
  }

  public CedarResourceType getResourceType() {
    return resourceType;
  }

  @JsonProperty(NodeProperty.Label.NAME)
  public String getName() {
    return name;
  }

  @JsonProperty(NodeProperty.Label.VERSION)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String getVersion() {
    return version;
  }

  @JsonProperty(NodeProperty.Label.PUBLICATION_STATUS)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String getPublicationStatus() {
    return publicationStatus;
  }
}
