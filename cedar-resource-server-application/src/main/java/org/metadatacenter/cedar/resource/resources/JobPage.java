package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.cedar.resource.search.JobStatus;
import org.metadatacenter.util.http.PagedListResponse;

import java.util.List;

/** One page of the jobs this server still knows, newest first, in CEDAR's body envelope. */
public final class JobPage extends PagedListResponse {

  private final List<JobStatus> jobs;

  public JobPage(List<JobStatus> jobs, String requestUrl, long totalCount, int limit, int offset) {
    this.jobs = jobs;
    page(requestUrl, totalCount, limit, offset, false);
  }

  public List<JobStatus> getJobs() {
    return jobs;
  }
}
