package org.metadatacenter.cedar.resource.customObjects;

import org.metadatacenter.model.resourceserver.CedarRSResource;

import java.util.List;

public class SearchResults {

  private int page;
  private int pageCount;
  private int pageSize;
  private int prevPage;
  private int nextPage;
  private List<CedarRSResource> collection;

  public SearchResults(int page, int pageCount, int pageSize, int prevPage, int nextPage, List<CedarRSResource>
      collection) {
    this.page = page;
    this.pageCount = pageCount;
    this.pageSize = pageSize;
    this.prevPage = prevPage;
    this.nextPage = nextPage;
    this.collection = collection;
  }

  public int getPage() {
    return page;
  }

  public void setPage(int page) {
    this.page = page;
  }

  public int getPageCount() {
    return pageCount;
  }

  public void setPageCount(int pageCount) {
    this.pageCount = pageCount;
  }

  public int getPageSize() {
    return pageSize;
  }

  public void setPageSize(int pageSize) {
    this.pageSize = pageSize;
  }

  public int getPrevPage() {
    return prevPage;
  }

  public void setPrevPage(int prevPage) {
    this.prevPage = prevPage;
  }

  public int getNextPage() {
    return nextPage;
  }

  public void setNextPage(int nextPage) {
    this.nextPage = nextPage;
  }

  public List<CedarRSResource> getCollection() {
    return collection;
  }

  public void setCollection(List<CedarRSResource> collection) {
    this.collection = collection;
  }

}