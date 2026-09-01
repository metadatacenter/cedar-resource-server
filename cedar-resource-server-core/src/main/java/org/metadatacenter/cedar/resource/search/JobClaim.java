package org.metadatacenter.cedar.resource.search;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
 * <p>Within the server that identity is object identity: the class deliberately inherits
 * {@code equals} from {@code Object}, because nothing needs to compare two claims and defining value
 * equality would only invite a comparison that defeats the check above. {@link #id()} is the same
 * identity written down for a caller, which holds no reference and can carry only a string. It is
 * what a start reports and what a status poll takes, so a caller can ask after the job it started
 * rather than after whatever is running now.
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

  private final String id;
  private final String command;
  private final Instant startedAt;

  public JobClaim(String command, Instant startedAt) {
    this.id = UUID.randomUUID().toString();
    this.command = command;
    this.startedAt = startedAt;
  }

  /** The claim as a caller can carry it: reported when the job starts, taken by a status poll. */
  public String id() {
    return id;
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
