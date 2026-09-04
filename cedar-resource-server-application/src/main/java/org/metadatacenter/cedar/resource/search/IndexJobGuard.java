package org.metadatacenter.cedar.resource.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One index rebuild at a time per index, and a record of how the last one ended.
 *
 * <p>The rebuild commands each started their own executor and answered 200 immediately, so two
 * requests ran two rebuilds over the same alias. That is not merely wasteful: each job finishes by
 * deleting every index for its alias except the one it built itself, so the job that finishes second
 * has its index deleted by the job that finishes first, and the alias is left naming an index that no
 * longer exists. The caller saw 200 either way, and the only account of what happened was in the log.
 *
 * <p>The guard is per index rather than per command because it is the index that cannot take two
 * writers. Regenerating the search index and emptying it are different commands over the same alias,
 * so they exclude each other; the search and rules indexes are independent, so they do not.
 *
 * <p>{@link #tryStart} decides and claims in one synchronized step. Reading a status and then setting
 * it would leave a window between the two calls in which multiple jobs could start.
 *
 * <p>Every exception a job can throw releases the index, but a job that never returns cannot be
 * released by any of those paths, and its claim would otherwise be held until the server restarted.
 * A claim therefore carries {@link JobClaim#DEADLINE}: past it the status reports the claim as
 * overdue, and {@link #reset} lets an operator take the index back. A reset invalidates the claim, so
 * the abandoned job cannot report over whoever holds the index next.
 *
 * <p>A job is also answerable by its own identifier, which is what the command that started it
 * returns. Reading the index alone answers a different question: it says what the last job over that
 * index did, and the two are the same job only until another command runs. {@link #find} answers for
 * the job the caller asked about, and stops knowing it once {@link #RETAINED_JOBS} later jobs have
 * pushed it out.
 */
public final class IndexJobGuard {

  private static final Logger log = LoggerFactory.getLogger(IndexJobGuard.class);

  @FunctionalInterface
  public interface Job {
    void run() throws Exception;
  }

  /** The indexes a rebuild can contend over. */
  public enum Index {
    SEARCH, RULES
  }

  public enum State {
    /** No job has run since this server started. */
    IDLE,
    RUNNING,
    /** The last job finished and did what it was asked to. */
    COMPLETE,
    /** The last job threw. The index may be half-rebuilt; {@code failure} says what happened. */
    FAILED,
    /** The last claim passed its deadline and an operator reset it. The job may still be running. */
    ABANDONED
  }

  /**
   * What became of the most recent job for one index, or that none has run. The timestamps are
   * rendered rather than held so that this reads the same over HTTP as it does in Java;
   * {@code deadlineAt} and {@code overdue} describe a running job and are empty otherwise.
   */
  public record Status(String jobId, State state, String command, String startedAt, String finishedAt,
                       String deadlineAt, boolean overdue, String failure) {
  }

  /**
   * One index as the guard holds it: the claim carries who took it and when, and outlives the job as
   * the record of what ran. {@code claim} is the identity a release must present, and it is the
   * current holder only while the state is {@link State#RUNNING}.
   */
  private record Entry(State state, JobClaim claim, Instant finishedAt, String failure) {

    static Entry idle() {
      return new Entry(State.IDLE, null, null, null);
    }

    Status render(Instant now) {
      boolean running = state == State.RUNNING;
      return new Status(claim == null ? null : claim.id(),
          state,
          claim == null ? null : claim.command(),
          claim == null ? null : claim.startedAt().toString(),
          finishedAt == null ? null : finishedAt.toString(),
          running ? claim.deadlineAt().toString() : null,
          running && claim.isOverdue(now),
          failure);
    }
  }

  private static final Map<Index, Entry> ENTRIES = new EnumMap<>(Index.class);

  static {
    for (Index index : Index.values()) {
      ENTRIES.put(index, Entry.idle());
    }
  }

  /**
   * How many jobs stay answerable by identifier. Jobs are held in memory, so this is a window rather
   * than a record: it is wide enough that a caller polling the job it started still finds it long
   * after the job finished, and narrow enough that an operator rebuilding an index every few minutes
   * for a day does not accumulate a status for each attempt.
   */
  static final int RETAINED_JOBS = 20;

  /**
   * Every job this guard still knows, oldest first, including any it is running now. An entry
   * here is the same object the index holds, so a job that finishes is answerable by identifier in
   * the state it finished in rather than in the state it was queued in.
   */
  private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

  private IndexJobGuard() {
  }

  /**
   * Record an index's job as both the index's current entry and the job's own, so it can be found by
   * either. A running job is never pushed out of the by-identifier window: it is the one entry a
   * caller is most likely to be polling, and evicting it would answer "no such job" about a rebuild
   * that is under way.
   */
  private static void hold(Index index, Entry entry) {
    ENTRIES.put(index, entry);
    if (entry.claim() == null) {
      return;
    }
    BY_ID.remove(entry.claim().id());
    BY_ID.put(entry.claim().id(), entry);
    Iterator<Map.Entry<String, Entry>> oldestFirst = BY_ID.entrySet().iterator();
    while (BY_ID.size() > RETAINED_JOBS && oldestFirst.hasNext()) {
      if (oldestFirst.next().getValue().state() != State.RUNNING) {
        oldestFirst.remove();
      }
    }
  }

  /**
   * Claim the index for a job, or report that one is already running. The caller must pass the claim
   * back to {@link #finish}, or the index stays claimed until an operator resets it. Passing the
   * deadline releases nothing on its own. It only allows the reset, so an abandoned job goes on
   * refusing every rebuild until someone runs one.
   */
  public static Optional<JobClaim> tryStart(Index index, String command) {
    return tryStart(index, command, Instant.now());
  }

  /** The claim instant is supplied so a test can place a claim on either side of its deadline. */
  static synchronized Optional<JobClaim> tryStart(Index index, String command, Instant now) {
    if (ENTRIES.get(index).state() == State.RUNNING) {
      return Optional.empty();
    }
    JobClaim claim = new JobClaim(command, now);
    hold(index, new Entry(State.RUNNING, claim, null, null));
    return Optional.of(claim);
  }

  /** Run a previously claimed job and release its index on every success or failure path. */
  public static void runClaimed(Index index, JobClaim claim, Job job) throws Exception {
    Throwable failure = null;
    try {
      job.run();
    } catch (Exception e) {
      failure = e;
      throw e;
    } catch (Error e) {
      failure = e;
      throw e;
    } finally {
      finish(index, claim, failure);
    }
  }

  /**
   * Release the index and record how the job ended. A failure keeps its message for the status. A
   * claim that is no longer the one held — reset as overdue, and possibly replaced since — releases
   * nothing and is logged, because the job reporting it no longer speaks for this index.
   */
  public static void finish(Index index, JobClaim claim, Throwable failure) {
    finish(index, claim, failure, Instant.now());
  }

  static synchronized void finish(Index index, JobClaim claim, Throwable failure, Instant now) {
    Entry entry = ENTRIES.get(index);
    if (entry.state() != State.RUNNING || entry.claim() != claim) {
      log.warn("A {} job reported on the {} index after its claim was taken away; ignoring the report",
          claim.command(), index.name().toLowerCase());
      return;
    }
    String message = failure == null ? null
        : failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    hold(index, new Entry(failure == null ? State.COMPLETE : State.FAILED, claim, now, message));
  }

  /**
   * Take back an index whose claim has passed its deadline, so the next rebuild can run. Reports
   * whether there was such a claim: an index that is idle, or busy with a claim still within its
   * deadline, is left exactly as it was.
   *
   * <p>This does not stop the abandoned job. Nothing can, which is why the deadline is long enough
   * that reaching it means the job is stuck rather than slow.
   */
  public static boolean reset(Index index) {
    return reset(index, Instant.now());
  }

  /**
   * The instant is supplied so a caller can weigh the deadline as of a chosen moment. A test takes
   * back a claim of any age by passing one past every deadline it could have set, the way
   * {@link ValueSetsImportStatusManager#reset(Instant)} is used for the import.
   */
  public static synchronized boolean reset(Index index, Instant now) {
    Entry entry = ENTRIES.get(index);
    if (entry.state() != State.RUNNING || !entry.claim().isOverdue(now)) {
      return false;
    }
    log.warn("Resetting the {} index: the {} job claimed at {} passed its {}-hour deadline",
        index.name().toLowerCase(), entry.claim().command(), entry.claim().startedAt(),
        JobClaim.DEADLINE.toHours());
    hold(index, new Entry(State.ABANDONED, entry.claim(), now,
        "the claim passed its " + JobClaim.DEADLINE.toHours() + "-hour deadline and was reset"));
    return true;
  }

  public static Status status(Index index) {
    return status(index, Instant.now());
  }

  static synchronized Status status(Index index, Instant now) {
    return ENTRIES.get(index).render(now);
  }

  /**
   * What became of one job, named by the identifier its command returned, and empty once no job
   * answers to it — an identifier from before the last restart, or one pushed out of the window.
   */
  public static Optional<Status> find(String jobId) {
    return find(jobId, Instant.now());
  }

  static synchronized Optional<Status> find(String jobId, Instant now) {
    return Optional.ofNullable(BY_ID.get(jobId)).map(entry -> entry.render(now));
  }

  public static Map<Index, Status> statuses() {
    return statuses(Instant.now());
  }

  static synchronized Map<Index, Status> statuses(Instant now) {
    Map<Index, Status> rendered = new EnumMap<>(Index.class);
    ENTRIES.forEach((index, entry) -> rendered.put(index, entry.render(now)));
    return rendered;
  }
}
