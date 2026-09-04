package org.metadatacenter.cedar.resource.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
 *
 * <p>That status describes the latest import, whichever one that is. An import is therefore also
 * answerable by the identifier its command returned, so a caller that started one learns what became
 * of that one rather than of whatever ran next. {@link #find} keeps answering for the last
 * {@link #RETAINED_JOBS} finished imports and forgets them after that, since they are held in memory
 * rather than stored.
 */
public class ValueSetsImportStatusManager {

  private static final Logger log = LoggerFactory.getLogger(ValueSetsImportStatusManager.class);

  public enum ImportStatus {
    NOT_YET_INITIATED, IN_PROGRESS, COMPLETE, ERROR
  }

  /**
   * One import as a caller reads it back, in the shape the status endpoint returns for the latest
   * one. {@code deadlineAt} and {@code overdue} describe a running import and are empty otherwise.
   */
  public record ImportJob(String jobId, ImportStatus importStatus, String startedAt, String finishedAt,
                          String deadlineAt, boolean overdue, String failure) {
  }

  /** How many finished imports stay answerable by identifier. */
  static final int RETAINED_JOBS = 20;

  private ImportStatus importStatus;

  /** Who holds the import, and the record of what ran once they no longer do. */
  private JobClaim claim;

  private Instant finishedAt;

  private String failure;

  /** What became of each import that has finished, oldest first, by the identifier of its claim. */
  private final Map<String, ImportJob> finished = new LinkedHashMap<>();

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
   * {@link #finish}, or the import stays claimed until an operator resets it. Passing the deadline
   * releases nothing on its own. It only allows the reset, so an abandoned import goes on refusing
   * every later one until someone runs it.
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
    remember(now);
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
    remember(now);
    return true;
  }

  /**
   * Keep what became of the import that just ended, so its own identifier still answers once a later
   * import has replaced the status above. Only a finished import is kept: the running one is rendered
   * as it stands, since {@code overdue} is true of it only from some instant onwards.
   */
  private void remember(Instant now) {
    finished.remove(claim.id());
    finished.put(claim.id(), snapshot(now));
    Iterator<String> oldestFirst = finished.keySet().iterator();
    while (finished.size() > RETAINED_JOBS && oldestFirst.hasNext()) {
      oldestFirst.next();
      oldestFirst.remove();
    }
  }

  /**
   * What became of one import, named by the identifier its command returned, and empty once no import
   * answers to it. The running import is rendered live rather than read back, so a poll on it reports
   * the deadline it has reached rather than the one it was queued under.
   */
  public synchronized Optional<ImportJob> find(String jobId) {
    if (claim != null && claim.id().equals(jobId) && importStatus == ImportStatus.IN_PROGRESS) {
      return Optional.of(snapshot(Instant.now()));
    }
    return Optional.ofNullable(finished.get(jobId));
  }

  /** The latest import in the same shape {@link #find} returns, which is what a start reports. */
  public synchronized ImportJob snapshot() {
    return snapshot(Instant.now());
  }

  private ImportJob snapshot(Instant now) {
    boolean running = importStatus == ImportStatus.IN_PROGRESS;
    return new ImportJob(claim == null ? null : claim.id(),
        importStatus,
        claim == null ? null : claim.startedAt().toString(),
        finishedAt == null ? null : finishedAt.toString(),
        running ? claim.deadlineAt().toString() : null,
        running && claim.isOverdue(now),
        failure);
  }

  /** The latest import as a caller can carry it, and null before any import has run. */
  public synchronized String getJobId() {
    return claim == null ? null : claim.id();
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
