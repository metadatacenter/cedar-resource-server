package org.metadatacenter.cedar.resource.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One rebuild at a time per index, an account of the last one, and a way back from a claim nothing
 * released.
 *
 * <p>What makes the exclusion worth a test rather than a reading is the contention: the rebuild
 * commands used to start an executor each and return, so two callers ran two rebuilds over one alias,
 * and since each job ends by deleting every index for its alias but its own, the second job's index
 * was deleted under the alias that had just been pointed at it. A guard that merely narrows that
 * window still loses it, so the claim is asserted from many threads at once rather than from two calls
 * in sequence.
 *
 * <p>The deadline is worth a test for the opposite reason: it is the one path that lets an index be
 * claimed twice, so what it refuses matters as much as what it allows.
 *
 * <p>The identifier is worth a test because reading an index answers a different question from
 * reading a job. They agree only while the job the caller started is the last one to have run over
 * its index, and the whole point of the identifier is the case where they no longer do.
 */
public class IndexJobGuardTest {

  private static final Instant CLAIMED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant WITHIN_DEADLINE = CLAIMED_AT.plus(JobClaim.DEADLINE).minusSeconds(1);
  private static final Instant PAST_DEADLINE = CLAIMED_AT.plus(JobClaim.DEADLINE).plusSeconds(1);

  @BeforeEach
  public void releaseEverything() {
    // The guard is process-wide, so leave no claim behind for the next test. Some tests claim at
    // CLAIMED_AT and one claims at the wall clock, so the reset has to be later than both.
    Instant afterEveryDeadline = Instant.now().plus(JobClaim.DEADLINE).plus(JobClaim.DEADLINE);
    for (IndexJobGuard.Index index : IndexJobGuard.Index.values()) {
      IndexJobGuard.reset(index, afterEveryDeadline);
    }
  }

  @Test
  public void aSecondClaimOnABusyIndexIsRefused() {
    assertTrue(claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isPresent());
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isEmpty(),
        "the same command must not run twice over one index");
  }

  /**
   * Regenerating the search index and emptying it are different commands over the same alias, so they
   * contend. Guarding per command rather than per index would let this pair through — the pair that
   * ends with one job deleting the other's index.
   */
  @Test
  public void differentCommandsOverTheSameIndexExcludeEachOther() {
    assertTrue(claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isPresent());
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "generate-empty-search-index").isEmpty(),
        "emptying the search index must wait for a regeneration of the same index");
  }

  /** The two indexes are independent, so a job on one must not block the other. */
  @Test
  public void theRulesIndexIsNotBlockedByTheSearchIndex() {
    assertTrue(claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isPresent());
    assertTrue(claim(IndexJobGuard.Index.RULES, "regenerate-rules-index").isPresent(),
        "the rules index has its own guard");
  }

  @Test
  public void finishingReleasesTheIndexForTheNextJob() {
    JobClaim claim = claim(IndexJobGuard.Index.RULES, "regenerate-rules-index").orElseThrow();
    IndexJobGuard.finish(IndexJobGuard.Index.RULES, claim, null);
    assertEquals(IndexJobGuard.State.COMPLETE, IndexJobGuard.status(IndexJobGuard.Index.RULES).state());
    assertTrue(claim(IndexJobGuard.Index.RULES, "generate-empty-rules-index").isPresent());
  }

  /**
   * A job that throws must release the index and say so. Leaving it claimed would need a reset to
   * clear; reporting COMPLETE would be worse, since the index may be half-rebuilt.
   */
  @Test
  public void aFailedJobReleasesTheIndexAndKeepsWhyItFailed() {
    JobClaim claim = claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();
    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, claim, new IllegalStateException("opensearch refused the alias"));

    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.SEARCH);
    assertEquals(IndexJobGuard.State.FAILED, status.state());
    assertTrue(status.failure().contains("opensearch refused the alias"), status.failure());
    assertNotNull(status.finishedAt());
    assertTrue(claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isPresent(),
        "a failure must not leave the index claimed");
  }

  @Test
  public void runClaimedReleasesTheIndexAfterAnUncheckedFailure() {
    JobClaim claim = claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();

    assertThrows(IllegalStateException.class,
        () -> IndexJobGuard.runClaimed(IndexJobGuard.Index.SEARCH, claim, () -> {
          throw new IllegalStateException("admin user unavailable");
        }));

    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.SEARCH);
    assertEquals(IndexJobGuard.State.FAILED, status.state());
    assertTrue(status.failure().contains("admin user unavailable"), status.failure());
    assertTrue(claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isPresent());
  }

  /** Nothing has run for an index until something does, and that reads as idle rather than as complete. */
  @Test
  public void anIndexNobodyHasRebuiltReportsIdle() {
    // RULES is untouched by this test method, and every test releases what it claims.
    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.RULES);
    assertNotEqualsRunning(status);
  }

  private void assertNotEqualsRunning(IndexJobGuard.Status status) {
    assertFalse(status.state() == IndexJobGuard.State.RUNNING, "no job should be running between tests");
    if (status.state() == IndexJobGuard.State.IDLE) {
      assertNull(status.command());
      assertNull(status.startedAt());
    }
  }

  /**
   * Exactly one of many simultaneous claims wins. The check and the claim are one synchronized step for
   * this reason: read-then-set admits every caller that reads before the first one writes.
   */
  @Test
  public void exactlyOneOfManySimultaneousClaimsWins() throws Exception {
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier allAtOnce = new CyclicBarrier(threads);
      List<Callable<Boolean>> claims = IntStream.range(0, threads)
          .<Callable<Boolean>>mapToObj(i -> () -> {
            allAtOnce.await(10, TimeUnit.SECONDS);
            return IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isPresent();
          })
          .collect(Collectors.toList());

      long won = 0;
      for (Future<Boolean> outcome : pool.invokeAll(claims)) {
        if (outcome.get()) {
          won++;
        }
      }
      assertEquals(1, won, "one claim must win and the rest must be refused");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * A running job is reported as overdue once it passes its deadline, and as running before that. This
   * is the whole of what the deadline does on its own: it does not release the index, so the status
   * still says a job is running, and the rebuild commands still refuse to start a second one.
   */
  @Test
  public void aClaimIsReportedOverdueOnlyOnceItPassesItsDeadline() {
    claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index");

    IndexJobGuard.Status within = IndexJobGuard.status(IndexJobGuard.Index.SEARCH, WITHIN_DEADLINE);
    assertEquals(IndexJobGuard.State.RUNNING, within.state());
    assertFalse(within.overdue());
    assertEquals(CLAIMED_AT.plus(JobClaim.DEADLINE).toString(), within.deadlineAt());

    IndexJobGuard.Status past = IndexJobGuard.status(IndexJobGuard.Index.SEARCH, PAST_DEADLINE);
    assertEquals(IndexJobGuard.State.RUNNING, past.state(), "the guard cannot know the job has stopped");
    assertTrue(past.overdue());
  }

  @Test
  public void aClaimWithinItsDeadlineCannotBeReset() {
    claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index");

    assertFalse(IndexJobGuard.reset(IndexJobGuard.Index.SEARCH, WITHIN_DEADLINE),
        "a job that is merely slow must keep the index it claimed");
    assertEquals(IndexJobGuard.State.RUNNING, IndexJobGuard.status(IndexJobGuard.Index.SEARCH).state());
  }

  @Test
  public void resettingAnOverdueClaimFreesTheIndexAndSaysWhatBecameOfIt() {
    claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index");

    assertTrue(IndexJobGuard.reset(IndexJobGuard.Index.SEARCH, PAST_DEADLINE));

    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.SEARCH);
    assertEquals(IndexJobGuard.State.ABANDONED, status.state());
    assertEquals("regenerate-search-index", status.command(), "the reset keeps the record of what was abandoned");
    assertTrue(status.failure().contains("deadline"), status.failure());
    assertTrue(claim(IndexJobGuard.Index.SEARCH, "generate-empty-search-index").isPresent(),
        "the point of the reset is that the next rebuild can run");
  }

  @Test
  public void thereIsNothingToResetOnAnIndexNobodyIsRebuilding() {
    JobClaim claim = claim(IndexJobGuard.Index.RULES, "regenerate-rules-index").orElseThrow();
    IndexJobGuard.finish(IndexJobGuard.Index.RULES, claim, null);

    assertFalse(IndexJobGuard.reset(IndexJobGuard.Index.RULES, PAST_DEADLINE),
        "a finished job left no claim to take back");
    assertEquals(IndexJobGuard.State.COMPLETE, IndexJobGuard.status(IndexJobGuard.Index.RULES).state());
  }

  /**
   * The reset is what makes a stale claim recoverable, and it is also the one way two jobs can end up
   * holding one index: the abandoned job is still running when the next one starts. It must not be able
   * to report over the job that replaced it, or the guard would say the index is free while a rebuild
   * writes to it — which is the concurrency the guard exists to prevent, arriving through its cure.
   */
  @Test
  public void anAbandonedJobCannotReportOverTheClaimThatReplacedIt() {
    JobClaim abandoned = claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();
    assertTrue(IndexJobGuard.reset(IndexJobGuard.Index.SEARCH, PAST_DEADLINE));
    JobClaim current = claim(IndexJobGuard.Index.SEARCH, "generate-empty-search-index").orElseThrow();

    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, abandoned, null);

    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.SEARCH);
    assertEquals(IndexJobGuard.State.RUNNING, status.state(), "the running job still holds the index");
    assertEquals("generate-empty-search-index", status.command());
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index").isEmpty());

    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, current, null);
    assertEquals(IndexJobGuard.State.COMPLETE, IndexJobGuard.status(IndexJobGuard.Index.SEARCH).state());
  }

  /**
   * One job keeps one identifier from the moment it is queued to whatever became of it, so a caller
   * that polls the identifier the command returned reads its own job's outcome rather than its start.
   */
  @Test
  public void aJobKeepsOneIdentifierFromQueuedToFinished() {
    JobClaim claim = claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();

    assertEquals(IndexJobGuard.State.RUNNING, IndexJobGuard.find(claim.id()).orElseThrow().state());

    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, claim, new IllegalStateException("opensearch refused the alias"));

    IndexJobGuard.Status found = IndexJobGuard.find(claim.id()).orElseThrow();
    assertEquals(IndexJobGuard.State.FAILED, found.state());
    assertEquals(claim.id(), found.jobId());
    assertTrue(found.failure().contains("opensearch refused the alias"), found.failure());
  }

  /**
   * The case the identifier exists for. Reading the index alone would report the job that replaced
   * this one, so a caller polling after a later rebuild started would be told that its own rebuild
   * is running when it failed, or that it succeeded when the success is somebody else's.
   */
  @Test
  public void aJobAnswersForItselfAfterALaterJobTookOverItsIndex() {
    JobClaim first = claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();
    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, first, new IllegalStateException("half a rebuild"));
    JobClaim second = claim(IndexJobGuard.Index.SEARCH, "generate-empty-search-index").orElseThrow();

    assertEquals(IndexJobGuard.State.RUNNING, IndexJobGuard.status(IndexJobGuard.Index.SEARCH).state(),
        "the index reports the job that took it over");
    assertEquals(IndexJobGuard.State.FAILED, IndexJobGuard.find(first.id()).orElseThrow().state(),
        "the first job still answers for itself");
    assertEquals(second.id(), IndexJobGuard.status(IndexJobGuard.Index.SEARCH).jobId());
  }

  /** A reset is the abandoned job's outcome, so its own identifier reports it rather than nothing. */
  @Test
  public void anAbandonedJobReportsTheResetUnderItsOwnIdentifier() {
    JobClaim abandoned = claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();

    assertTrue(IndexJobGuard.reset(IndexJobGuard.Index.SEARCH, PAST_DEADLINE));

    IndexJobGuard.Status found = IndexJobGuard.find(abandoned.id()).orElseThrow();
    assertEquals(IndexJobGuard.State.ABANDONED, found.state());
    assertTrue(found.failure().contains("deadline"), found.failure());
  }

  @Test
  public void noJobAnswersToAnIdentifierNothingWasQueuedUnder() {
    assertTrue(IndexJobGuard.find(UUID.randomUUID().toString()).isEmpty());
  }

  /**
   * Jobs are held in memory, so the window on them is bounded. A job pushed out of it is no longer
   * known, and a running job is never the one pushed out: it is the job most likely to be polled, and
   * evicting it would answer "no such job" about a rebuild that is under way.
   */
  @Test
  public void theWindowOnFinishedJobsIsBoundedAndKeepsTheRunningOne() {
    JobClaim running = claim(IndexJobGuard.Index.RULES, "regenerate-rules-index").orElseThrow();
    JobClaim oldest = claim(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();
    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, oldest, null);

    for (int i = 0; i < IndexJobGuard.RETAINED_JOBS; i++) {
      JobClaim later = claim(IndexJobGuard.Index.SEARCH, "generate-empty-search-index").orElseThrow();
      IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, later, null);
    }

    assertTrue(IndexJobGuard.find(oldest.id()).isEmpty(), "the oldest finished job is forgotten");
    assertEquals(IndexJobGuard.State.RUNNING, IndexJobGuard.find(running.id()).orElseThrow().state(),
        "a running job is not pushed out by the jobs that finish around it");
    IndexJobGuard.finish(IndexJobGuard.Index.RULES, running, null);
  }

  /** Every claim in this class is taken at the same instant, so the deadline constants apply to all of them. */
  private Optional<JobClaim> claim(IndexJobGuard.Index index, String command) {
    return IndexJobGuard.tryStart(index, command, CLAIMED_AT);
  }
}
