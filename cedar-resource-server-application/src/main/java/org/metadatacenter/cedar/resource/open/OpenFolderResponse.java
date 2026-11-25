package org.metadatacenter.cedar.resource.open;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.util.FolderServerNodeContext;

import java.util.List;
import java.util.Map;

/**
 * Public, caller-independent response for an anonymously readable folder.
 *
 * <p>OpenView relays this body under its own URL and serves the same shape as its
 * {@code OpenViewFolderResponse}, so a field added here is a field added to both contracts.</p>
 */
@Schema(name = "OpenFolderResponse")
public class OpenFolderResponse {

  private final NodeListRequest request;
  private final long totalCount;
  private final long currentOffset;
  private final Map<String, String> paging;
  private final List<OpenResourceSummary> resources;
  private final List<OpenPathSegment> pathInfo;

  public OpenFolderResponse(NodeListRequest request, long totalCount, Map<String, String> paging,
                            List<FolderServerResourceExtract> resources,
                            List<FolderServerResourceExtract> pathInfo) {
    this.request = request;
    this.totalCount = totalCount;
    this.currentOffset = request.getOffset();
    this.paging = paging;
    this.resources = resources.stream().map(OpenResourceSummary::from).toList();
    this.pathInfo = pathInfo.stream().map(OpenPathSegment::from).toList();
  }

  public NodeListRequest getRequest() {
    return request;
  }

  public long getTotalCount() {
    return totalCount;
  }

  public long getCurrentOffset() {
    return currentOffset;
  }

  public Map<String, String> getPaging() {
    return paging;
  }

  public List<OpenResourceSummary> getResources() {
    return resources;
  }

  public List<OpenPathSegment> getPathInfo() {
    return pathInfo;
  }

  public NodeListQueryType getNodeListQueryType() {
    return NodeListQueryType.FOLDER_CONTENT;
  }

  @JsonProperty("@context")
  public Map<String, String> getContext() {
    return FolderServerNodeContext.getContext();
  }
}
