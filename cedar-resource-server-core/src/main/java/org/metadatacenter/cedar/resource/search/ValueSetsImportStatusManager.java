package org.metadatacenter.cedar.resource.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * One value sets ontology import at a time, and a record of how the last one ended.
 *
 * <p>The import is claimed and released the way an index rebuild is, for the same reasons and with
 * the same failure to avoid: a claim that nothing releases refuses every later import, and before
 * the claim carried a deadline the only way out was a server restart. {@link #tryStart} decides and
 * claims in one synchronized step, {@link #finish} releases on the claim it was given, and
 * {@link #reset} takes the import back once its claim has passed {@link JobClaim#DEADLINE}.
 *
 * <p>The four {@link ImportStatus} values are a wire contract: the caDSR ingestor polls
 * {@code /command/load-valuesets-ontology-status}, reads {@code importStatus} and acts on the name
 * it finds there. An import abandoned as overdue reports {@code ERROR}, which is both true of it and
 * a value that caller already understands.
 */
public class ValueSetsImportStatusManager {

  private static final Logger log = LoggerFactory.getLogger(ValueSetsImportStatusManager.class);

  public enum ImportStatus {
    NOT_YET_INITIATED, IN_PROGRESS, COMPLETE, ERROR
  }

  private ImportStatus importStatus;

  /** Who holds the import, and the record of what ran once they no longer do. */
  private JobClaim claim;

  private Instant finishedAt;

  private String failure;

  private static ValueSetsImportStatusManager singleInstance;

  private ValueSetsImportStatusManager() {
    importStatus = ImportStatus.NOT_YET_INITIATED;
  }

  public static synchronized ValueSetsImportStatusManager getInstance() {
    if (singleInstance == null) {
      singleInstance = new ValueSetsImportStatusManager();
    }
    return singleInstance;
  }

  /**
   * Claim the import, or report that one is already running. The caller must pass the claim back to
   * {@link #finish}, or the import stays claimed until its deadline passes and an operator resets it.
   */
  public Optional<JobClaim> tryStart() {
    return tryStart(Instant.now());
  }

  /** The claim instant is supplied so a test can place a claim on either side of its deadline. */
  public synchronized Optional<JobClaim> tryStart(Instant now) {
    if (importStatus == ImportStatus.IN_PROGRESS) {
      return Optional.empty();
    }
    claim = new JobClaim("load-valuesets-ontology", now);
    importStatus = ImportStatus.IN_PROGRESS;
    finishedAt = null;
    failure = null;
    return Optional.of(claim);
  }

  /**
   * Release the import and record how it ended. A claim that is no longer the one held — reset as
   * overdue, and possibly replaced since — releases nothing, because the job reporting it no longer
   * speaks for the import.
   */
  public void finish(JobClaim claim, Throwable failure) {
    finish(claim, failure, Instant.now());
  }

  public synchronized void finish(JobClaim claim, Throwable failure, Instant now) {
    if (importStatus != ImportStatus.IN_PROGRESS || this.claim != claim) {
      log.warn("A value sets ontology import reported after its claim was taken away; ignoring the report");
      return;
    }
    importStatus = failure == null ? ImportStatus.COMPLETE : ImportStatus.ERROR;
    finishedAt = now;
    this.failure = failure == null ? null
        : failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
  }

  /**
   * Take back an import whose claim has passed its deadline, so the next one can run. Reports whether
   * there was such a claim; an import that is not running, or one still within its deadline, is left
   * exactly as it was. This does not stop the abandoned import.
   */
  public boolean reset() {
    return reset(Instant.now());
  }

  public synchronized boolean reset(Instant now) {
    if (importStatus != ImportStatus.IN_PROGRESS || !claim.isOverdue(now)) {
      return false;
    }
    log.warn("Resetting the value sets ontology import: the claim taken at {} passed its {}-hour deadline",
        claim.startedAt(), JobClaim.DEADLINE.toHours());
    importStatus = ImportStatus.ERROR;
    finishedAt = now;
    failure = "the claim passed its " + JobClaim.DEADLINE.toHours() + "-hour deadline and was reset";
    return true;
  }

  public synchronized ImportStatus getImportStatus() {
    return importStatus;
  }

  public synchronized String getStartedAt() {
    return claim == null ? null : claim.startedAt().toString();
  }

  public synchronized String getFinishedAt() {
    return finishedAt == null ? null : finishedAt.toString();
  }

  /** When the running import stops being believed, and empty when none is running. */
  public synchronized String getDeadlineAt() {
    return importStatus == ImportStatus.IN_PROGRESS ? claim.deadlineAt().toString() : null;
  }

  public synchronized boolean isOverdue() {
    return importStatus == ImportStatus.IN_PROGRESS && claim.isOverdue(Instant.now());
  }

  public synchronized String getFailure() {
    return failure;
  }
}
