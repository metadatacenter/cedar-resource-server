package org.metadatacenter.cedar.resource.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metadatacenter.server.search.util.IndexingProgress;

/**
 * One job as {@code GET /jobs/{id}} reports it, whatever kind of job it is.
 *
 * <p>Every server that runs jobs answers in this shape, so a caller polls a search index rebuild, a
 * value sets import and the worker's inclusion-subgraph regeneration the same way. The fields after
 * {@code failure} belong to one kind each and are absent for the others. {@code importStatus} is the
 * value sets import's own state under the name the caDSR ingestor has always read.
 */
public record JobStatus(String jobId, String kind, String command, State state, String startedAt,
                        String finishedAt, String deadlineAt, boolean overdue, String failure,
                        @JsonInclude(JsonInclude.Include.NON_NULL) IndexingProgress.Snapshot progress,
                        @JsonInclude(JsonInclude.Include.NON_NULL)
                        ValueSetsImportStatusManager.ImportStatus importStatus) {

  /** The kinds of job this server runs, as {@code kind} and the listing's filter spell them. */
  public static final String SEARCH_INDEX = "search-index";
  public static final String VALUE_SETS_IMPORT = "value-sets-import";

  /** Where a job ended up. A job is never idle: an index or import nothing has run over has no job. */
  public enum State {
    QUEUED, RUNNING, COMPLETE, FAILED, ABANDONED, INTERRUPTED
  }

  static JobStatus of(IndexJobGuard.Status status) {
    return new JobStatus(status.jobId(), SEARCH_INDEX, status.command(), State.valueOf(status.state().name()),
        status.startedAt(), status.finishedAt(), status.deadlineAt(), status.overdue(), status.failure(),
        status.progress(), null);
  }

  static JobStatus of(ValueSetsImportStatusManager.ImportJob job) {
    State state = switch (job.importStatus()) {
      case NOT_YET_INITIATED -> State.QUEUED;
      case IN_PROGRESS -> State.RUNNING;
      case COMPLETE -> State.COMPLETE;
      case ERROR -> job.abandoned() ? State.ABANDONED : State.FAILED;
    };
    return new JobStatus(job.jobId(), VALUE_SETS_IMPORT, job.command(), state, job.startedAt(),
        job.finishedAt(), job.deadlineAt(), job.overdue(), job.failure(), null, job.importStatus());
  }
}
