package org.metadatacenter.server.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ResourceListRequest {
  private String path;
  @JsonProperty("resource_types")
  private List<String> resourceTypes;
  private long limit;
  private long offset;
  private List<String> sort;

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public List<String> getResourceTypes() {
    return resourceTypes;
  }

  public void setResourceTypes(List<String> resourceTypes) {
    this.resourceTypes = resourceTypes;
  }

  public long getLimit() {
    return limit;
  }

  public void setLimit(long limit) {
    this.limit = limit;
  }

  public long getOffset() {
    return offset;
  }

  public void setOffset(long offset) {
    this.offset = offset;
  }

  public List<String> getSort() {
    return sort;
  }

  public void setSort(List<String> sort) {
    this.sort = sort;
  }
}
