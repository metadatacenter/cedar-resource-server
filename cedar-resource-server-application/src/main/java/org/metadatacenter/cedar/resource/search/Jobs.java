package org.metadatacenter.cedar.resource.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every job this server runs, read as one collection.
 *
 * <p>Index rebuilds and value sets imports are claimed and recorded separately, because they contend
 * over different things. A caller asking after a job has only its identifier, so it is looked up in
 * both, and either answers in the one {@link JobStatus} shape.
 */
public final class Jobs {

  /** What a reset found, so the route can say why nothing was reset. */
  public enum ResetOutcome {
    RESET, NOT_RUNNING, WITHIN_DEADLINE
  }

  private Jobs() {
  }

  public static Optional<JobStatus> find(String jobId) {
    Optional<JobStatus> index = IndexJobGuard.find(jobId).map(JobStatus::of);
    if (index.isPresent()) {
      return index;
    }
    return ValueSetsImportStatusManager.getInstance().find(jobId).map(JobStatus::of);
  }

  /**
   * The jobs of one kind, or of every kind when none is named, newest first, so the first is the one
   * running now or the one that ran last.
   */
  public static List<JobStatus> list(Optional<String> kind) {
    List<JobStatus> jobs = new ArrayList<>();
    if (kind.isEmpty() || kind.get().equals(JobStatus.SEARCH_INDEX)) {
      IndexJobGuard.jobs().forEach(status -> jobs.add(JobStatus.of(status)));
    }
    if (kind.isEmpty() || kind.get().equals(JobStatus.VALUE_SETS_IMPORT)) {
      ValueSetsImportStatusManager.getInstance().jobs().forEach(job -> jobs.add(JobStatus.of(job)));
    }
    jobs.sort(Comparator.comparing(Jobs::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
    return jobs;
  }

  /** Compared as instants. As text, two in the same second sort wrongly when only one has a fraction. */
  private static Instant startedAt(JobStatus job) {
    return job.startedAt() == null ? null : Instant.parse(job.startedAt());
  }

  /**
   * Take back the claim a job holds, if it has stopped reporting progress. A job holds a claim only
   * while it runs, so a finished job has nothing to reset.
   */
  public static ResetOutcome reset(JobStatus job) {
    if (job.state() != JobStatus.State.RUNNING) {
      return ResetOutcome.NOT_RUNNING;
    }
    Instant now = Instant.now();
    boolean reset = job.kind().equals(JobStatus.SEARCH_INDEX)
        ? IndexJobGuard.reset(job.jobId(), now)
        : ValueSetsImportStatusManager.getInstance().reset(job.jobId(), now);
    if (reset) {
      return ResetOutcome.RESET;
    }
    // The job may have finished between the read and the reset, which is not the deadline's doing.
    return find(job.jobId()).filter(again -> again.state() == JobStatus.State.RUNNING).isPresent()
        ? ResetOutcome.WITHIN_DEADLINE : ResetOutcome.NOT_RUNNING;
  }
}
