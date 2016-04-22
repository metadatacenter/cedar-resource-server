package org.metadatacenter.cedar.resource.model;

import org.metadatacenter.model.request.ResourceListRequest;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.model.resourceserver.CedarRSResource;

import java.util.List;
import java.util.Map;

public class MyResourceListResponse {

  private ResourceListRequest request;
  private long totalCount;
  private long currentOffset;
  private Map<String, String> paging;
  private List<CedarRSResource> resources;
  private List<CedarRSFolder> pathInfo;

  public ResourceListRequest getRequest() {
    return request;
  }

  public void setRequest(ResourceListRequest request) {
    this.request = request;
  }

  public long getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(long totalCount) {
    this.totalCount = totalCount;
  }

  public long getCurrentOffset() {
    return currentOffset;
  }

  public void setCurrentOffset(long currentOffset) {
    this.currentOffset = currentOffset;
  }

  public Map<String, String> getPaging() {
    return paging;
  }

  public void setPaging(Map<String, String> paging) {
    this.paging = paging;
  }

  public List<CedarRSResource> getResources() {
    return resources;
  }

  public void setResources(List<CedarRSResource> resources) {
    this.resources = resources;
  }

  public List<CedarRSFolder> getPathInfo() {
    return pathInfo;
  }

  public void setPathInfo(List<CedarRSFolder> pathInfo) {
    this.pathInfo = pathInfo;
  }
}
