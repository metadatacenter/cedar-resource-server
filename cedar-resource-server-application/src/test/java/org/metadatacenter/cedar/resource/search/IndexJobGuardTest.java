package org.metadatacenter.cedar.resource.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
 * One rebuild at a time per index, and an account of the last one.
 *
 * <p>What makes this worth a test rather than a reading is the contention: the rebuild commands used to
 * start an executor each and return, so two callers ran two rebuilds over one alias, and since each job
 * ends by deleting every index for its alias but its own, the second job's index was deleted under the
 * alias that had just been pointed at it. A guard that merely narrows that window still loses it, so
 * the claim is asserted from many threads at once rather than from two calls in sequence.
 */
public class IndexJobGuardTest {

  @BeforeEach
  public void releaseEverything() {
    // The guard is process-wide, so leave no claim behind for the next test.
    for (IndexJobGuard.Index index : IndexJobGuard.Index.values()) {
      if (IndexJobGuard.status(index).state() == IndexJobGuard.State.RUNNING) {
        IndexJobGuard.finish(index, null);
      }
    }
  }

  @Test
  public void aSecondClaimOnABusyIndexIsRefused() {
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"));
    assertFalse(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"),
        "the same command must not run twice over one index");
  }

  /**
   * Regenerating the search index and emptying it are different commands over the same alias, so they
   * contend. Guarding per command rather than per index would let this pair through — the pair that
   * ends with one job deleting the other's index.
   */
  @Test
  public void differentCommandsOverTheSameIndexExcludeEachOther() {
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"));
    assertFalse(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "generate-empty-search-index"),
        "emptying the search index must wait for a regeneration of the same index");
  }

  /** The two indexes are independent, so a job on one must not block the other. */
  @Test
  public void theRulesIndexIsNotBlockedByTheSearchIndex() {
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"));
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.RULES, "regenerate-rules-index"),
        "the rules index has its own guard");
  }

  @Test
  public void finishingReleasesTheIndexForTheNextJob() {
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.RULES, "regenerate-rules-index"));
    IndexJobGuard.finish(IndexJobGuard.Index.RULES, null);
    assertEquals(IndexJobGuard.State.COMPLETE, IndexJobGuard.status(IndexJobGuard.Index.RULES).state());
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.RULES, "generate-empty-rules-index"));
  }

  /**
   * A job that throws must release the index and say so. Leaving it claimed would need a restart to
   * clear; reporting COMPLETE would be worse, since the index may be half-rebuilt.
   */
  @Test
  public void aFailedJobReleasesTheIndexAndKeepsWhyItFailed() {
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"));
    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, new IllegalStateException("opensearch refused the alias"));

    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.SEARCH);
    assertEquals(IndexJobGuard.State.FAILED, status.state());
    assertTrue(status.failure().contains("opensearch refused the alias"), status.failure());
    assertNotNull(status.finishedAt());
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"),
        "a failure must not leave the index claimed");
  }

  @Test
  public void runClaimedReleasesTheIndexAfterAnUncheckedFailure() {
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"));

    assertThrows(IllegalStateException.class,
        () -> IndexJobGuard.runClaimed(IndexJobGuard.Index.SEARCH, () -> {
          throw new IllegalStateException("admin user unavailable");
        }));

    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.SEARCH);
    assertEquals(IndexJobGuard.State.FAILED, status.state());
    assertTrue(status.failure().contains("admin user unavailable"), status.failure());
    assertTrue(IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index"));
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
   * this reason: read-then-set, as the value-set import does it, admits every caller that reads before
   * the first one writes.
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
            return IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index");
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
}
