package org.metadatacenter.cedar.resource.search;

import java.time.Duration;
import java.time.Instant;

/**
 * A claim on work that only one job may run at a time, and the deadline past which the claim is no
 * longer believed.
 *
 * <p>A claim is the job's identity as much as its exclusion. The holder passes it back to release
 * what it took, so a guard whose claim was taken away — because it passed its deadline and an
 * operator reset it — recognizes the stale claim and ignores what it reports. Without that identity
 * an abandoned job could finish hours later and report over a claim somebody else now holds, which
 * would leave the guard saying nothing is running while a rebuild is under way: the concurrency the
 * guard exists to prevent, reintroduced by the recovery path.
 *
 * <p>Identity is object identity. This class deliberately inherits {@code equals} from
 * {@code Object}, and defining value equality on it would defeat the check above, since two claims
 * taken for the same command at the same instant would then be interchangeable.
 *
 * <p>Passing the deadline neither stops a job nor releases what it holds. It says only that the
 * claim is no longer believed, which lets a status report say so and lets an operator reset it. The
 * value is generous for that reason: it is never reached by a healthy job, however large the
 * repository being indexed, and setting it low would cost far more than waiting out a leak — two
 * rebuilds over one alias end with the one that finishes first deleting the other's index.
 */
public final class JobClaim {

  /** How long a claim is believed before it is reported overdue and an operator may reset it. */
  public static final Duration DEADLINE = Duration.ofHours(6);

  private final String command;
  private final Instant startedAt;

  public JobClaim(String command, Instant startedAt) {
    this.command = command;
    this.startedAt = startedAt;
  }

  public String command() {
    return command;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant deadlineAt() {
    return startedAt.plus(DEADLINE);
  }

  public boolean isOverdue(Instant now) {
    return now.isAfter(deadlineAt());
  }
}
