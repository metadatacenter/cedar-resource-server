package org.metadatacenter.cedar.resource.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One value sets ontology import at a time, and a way back from one that never finished.
 *
 * <p>The import is claimed the way an index rebuild is, and for the same reason: before the claim
 * carried a deadline, an import that never returned left {@code IN_PROGRESS} standing, refused every
 * later import, and could be cleared only by restarting the server.
 *
 * <p>The identifier is asserted for the reason it exists: the status above describes the latest
 * import, so a caller reading it after a second import started would be told about that one.
 */
class ValueSetsImportStatusManagerTest {

  private static final Instant CLAIMED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant WITHIN_DEADLINE = CLAIMED_AT.plus(JobClaim.DEADLINE).minusSeconds(1);
  private static final Instant PAST_DEADLINE = CLAIMED_AT.plus(JobClaim.DEADLINE).plusSeconds(1);

  private final ValueSetsImportStatusManager manager = ValueSetsImportStatusManager.getInstance();

  /**
   * The manager is a process-wide singleton, so a claim left outstanding crosses from one test to
   * the next and from one class to the next. Some tests claim at CLAIMED_AT and one claims at the
   * wall clock, so the reset instant has to be later than both.
   */
  @BeforeEach
  void startFromAnUnclaimedImport() {
    takeBackAnyClaim();
  }

  /**
   * Tests here deliberately leave an import in progress, and an import in progress is refused
   * however long ago it was claimed, since only a reset weighs the deadline. Clearing it here keeps
   * the next class that asks for an import from being refused instead of queued.
   */
  @AfterEach
  void leaveNoClaimForTheNextTest() {
    takeBackAnyClaim();
  }

  private void takeBackAnyClaim() {
    manager.reset(Instant.now().plus(JobClaim.DEADLINE).plus(JobClaim.DEADLINE));
  }

  @Test
  void exactlyOneOfManySimultaneousClaimsWins() throws Exception {
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier allAtOnce = new CyclicBarrier(threads);
      List<Callable<Boolean>> claims = IntStream.range(0, threads)
          .<Callable<Boolean>>mapToObj(i -> () -> {
            allAtOnce.await(10, TimeUnit.SECONDS);
            return manager.tryStart().isPresent();
          })
          .collect(Collectors.toList());

      long won = 0;
      for (Future<Boolean> outcome : pool.invokeAll(claims)) {
        if (outcome.get()) {
          won++;
        }
      }
      assertEquals(1, won);
      assertEquals(ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS, manager.getImportStatus());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void anImportWithinItsDeadlineCannotBeReset() {
    manager.tryStart(CLAIMED_AT);

    assertEquals(CLAIMED_AT.plus(JobClaim.DEADLINE).toString(), manager.getDeadlineAt());
    assertFalse(manager.reset(WITHIN_DEADLINE), "an import that is merely slow must keep the claim it took");
    assertEquals(ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS, manager.getImportStatus());
  }

  /**
   * An abandoned import reports ERROR rather than a state of its own. The caDSR ingestor polls this
   * status and acts on the four names it knows, and ERROR is both true of an import that did not
   * finish and a name that caller already handles.
   */
  @Test
  void resettingAnOverdueImportFreesItAndReportsAnError() {
    manager.tryStart(CLAIMED_AT);

    assertTrue(manager.reset(PAST_DEADLINE));

    assertEquals(ValueSetsImportStatusManager.ImportStatus.ERROR, manager.getImportStatus());
    assertTrue(manager.getFailure().contains("deadline"), manager.getFailure());
    assertTrue(manager.tryStart().isPresent(), "the point of the reset is that the next import can run");
  }

  /**
   * One import keeps one identifier from the moment it is queued to whatever became of it, and keeps
   * answering under it once a later import has replaced the status above.
   */
  @Test
  void anImportAnswersForItselfAfterALaterImportReplacedTheStatus() {
    JobClaim first = manager.tryStart(CLAIMED_AT).orElseThrow();
    manager.finish(first, new IllegalStateException("no path configured"));
    JobClaim second = manager.tryStart().orElseThrow();

    assertEquals(ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS, manager.getImportStatus(),
        "the status describes the import that replaced the first one");
    assertEquals(second.id(), manager.getJobId());

    ValueSetsImportStatusManager.ImportJob found = manager.find(first.id()).orElseThrow();
    assertEquals(ValueSetsImportStatusManager.ImportStatus.ERROR, found.importStatus());
    assertTrue(found.failure().contains("no path configured"), found.failure());

    manager.finish(second, null);
  }

  /** A reset is the abandoned import's outcome, so its own identifier reports it rather than nothing. */
  @Test
  void anAbandonedImportReportsTheResetUnderItsOwnIdentifier() {
    JobClaim abandoned = manager.tryStart(CLAIMED_AT).orElseThrow();

    assertTrue(manager.reset(PAST_DEADLINE));

    ValueSetsImportStatusManager.ImportJob found = manager.find(abandoned.id()).orElseThrow();
    assertEquals(ValueSetsImportStatusManager.ImportStatus.ERROR, found.importStatus());
    assertTrue(found.failure().contains("deadline"), found.failure());
  }

  /**
   * The running import is rendered as it stands rather than read back from what was recorded when it
   * was queued, so a poll on it reports the deadline it has reached.
   */
  @Test
  void theRunningImportIsReportedAsItStands() {
    JobClaim running = manager.tryStart(CLAIMED_AT).orElseThrow();

    ValueSetsImportStatusManager.ImportJob found = manager.find(running.id()).orElseThrow();
    assertEquals(ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS, found.importStatus());
    assertEquals(CLAIMED_AT.plus(JobClaim.DEADLINE).toString(), found.deadlineAt());
    assertTrue(found.overdue(), "a claim taken in the past has passed its deadline by now");
  }

  @Test
  void noImportAnswersToAnIdentifierNothingWasQueuedUnder() {
    assertTrue(manager.find(UUID.randomUUID().toString()).isEmpty());
  }

  @Test
  void thereIsNothingToResetWhenNoImportIsRunning() {
    ValueSetsImportStatusManager.ImportStatus before = manager.getImportStatus();

    assertFalse(manager.reset(PAST_DEADLINE));
    assertEquals(before, manager.getImportStatus());
  }

  /**
   * An import abandoned as overdue may still be running, and must not report over the import that
   * replaced it: the status would then say COMPLETE while an import was under way, and a third caller
   * would start alongside it.
   */
  @Test
  void anAbandonedImportCannotReportOverTheClaimThatReplacedIt() {
    JobClaim abandoned = manager.tryStart(CLAIMED_AT).orElseThrow();
    assertTrue(manager.reset(PAST_DEADLINE));
    JobClaim current = manager.tryStart().orElseThrow();

    manager.finish(abandoned, null);

    assertEquals(ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS, manager.getImportStatus());
    assertTrue(manager.tryStart().isEmpty());

    manager.finish(current, null);
    assertEquals(ValueSetsImportStatusManager.ImportStatus.COMPLETE, manager.getImportStatus());
  }
}
