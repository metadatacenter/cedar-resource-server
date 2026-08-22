package org.metadatacenter.cedar.resource.search;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

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
 * it, as the value-set import does, leaves the window between the two calls open — narrower than no
 * check at all, but still a window.
 */
public final class IndexJobGuard {

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
    FAILED
  }

  /** What became of the most recent job for one index, or that none has run. */
  public record Status(State state, String command, String startedAt, String finishedAt, String failure) {

    static Status idle() {
      return new Status(State.IDLE, null, null, null, null);
    }
  }

  private static final Map<Index, Status> STATUS = new EnumMap<>(Index.class);

  static {
    for (Index index : Index.values()) {
      STATUS.put(index, Status.idle());
    }
  }

  private IndexJobGuard() {
  }

  /**
   * Claim the index for a job, or report that one is already running. The caller must call
   * {@link #finish} for every claim it takes, or the index stays claimed until the server restarts.
   */
  public static synchronized boolean tryStart(Index index, String command) {
    if (STATUS.get(index).state() == State.RUNNING) {
      return false;
    }
    STATUS.put(index, new Status(State.RUNNING, command, Instant.now().toString(), null, null));
    return true;
  }

  /** Release the index and record how the job ended. A failure keeps its message for the status. */
  public static synchronized void finish(Index index, Throwable failure) {
    Status running = STATUS.get(index);
    String message = failure == null ? null
        : failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    STATUS.put(index, new Status(failure == null ? State.COMPLETE : State.FAILED,
        running.command(), running.startedAt(), Instant.now().toString(), message));
  }

  public static synchronized Status status(Index index) {
    return STATUS.get(index);
  }

  public static synchronized Map<Index, Status> statuses() {
    return new EnumMap<>(STATUS);
  }
}
