package org.metadatacenter.cedar.resource.resources;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.search.IndexJobGuard;
import org.metadatacenter.cedar.resource.search.JobClaim;
import org.metadatacenter.cedar.resource.search.ValueSetsImportStatusManager;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim that is never handed to a running job is released rather than held.
 *
 * <p>{@link IndexJobGuard#runClaimed} covers every path the job itself can take, but the claim is
 * taken before the executor exists. A thread that cannot be created, or a task that is refused,
 * leaves the window between the two: without this the index stays claimed on behalf of a job that
 * never started, and every rebuild is refused with a 409 naming it until the claim passes its
 * six-hour deadline and an operator resets it.
 *
 * <p>The value sets import is claimed and handed over the same way, so it is asserted here too.
 */
public class ClaimedIndexJobSubmissionTest {

  private static final IndexJobGuard.Index INDEX = IndexJobGuard.Index.SEARCH;

  /**
   * Both claims live in process-wide state — {@link IndexJobGuard} in statics, the import manager in
   * a singleton — so a test that ends with either still claimed decides whether the next one can
   * claim at all. Each method here opens with {@code tryStart().orElseThrow()}, which turns that
   * into a NoSuchElementException naming nothing, and the order methods run in is not fixed: these
   * passed locally and failed on the runner for that reason alone.
   *
   * <p>Clearing both before each test makes every one of them start from the state it assumes,
   * whatever ran before it.
   */
  @BeforeEach
  public void releaseAnyClaimLeftByAnotherTest() {
    releaseBothClaims();
  }

  /** And leaves the same clean state behind, so this class cannot be the one that breaks another. */
  @AfterEach
  public void leaveNoClaimForTheNextTest() {
    releaseBothClaims();
  }

  private static void releaseBothClaims() {
    IndexJobGuard.reset(INDEX);
    ValueSetsImportStatusManager.getInstance().reset();
  }

  @Test
  public void aRefusedTaskReleasesTheClaim() {
    JobClaim claim = IndexJobGuard.tryStart(INDEX, "regenerate-search-index").orElseThrow();

    ExecutorService refuses = Executors.newSingleThreadExecutor();
    refuses.shutdown();  // a shut-down executor refuses every task it is offered

    assertThrows(RejectedExecutionException.class,
        () -> CommandSearchResource.submitClaimedIndexJob(INDEX, claim, "search index regeneration",
            () -> { }, () -> refuses));

    assertEquals(IndexJobGuard.State.FAILED, IndexJobGuard.status(INDEX).state());
    releaseWhatTheNextJobCanClaim(IndexJobGuard.tryStart(INDEX, "regenerate-search-index"),
        "a task the executor refused must not leave the index claimed");
  }

  @Test
  public void anExecutorThatCannotBeCreatedReleasesTheClaim() {
    JobClaim claim = IndexJobGuard.tryStart(INDEX, "regenerate-search-index").orElseThrow();

    assertThrows(OutOfMemoryError.class,
        () -> CommandSearchResource.submitClaimedIndexJob(INDEX, claim, "search index regeneration",
            () -> { }, () -> { throw new OutOfMemoryError("unable to create native thread"); }));

    IndexJobGuard.Status status = IndexJobGuard.status(INDEX);
    assertEquals(IndexJobGuard.State.FAILED, status.state());
    assertTrue(status.failure().contains("unable to create native thread"), status.failure());
    releaseWhatTheNextJobCanClaim(IndexJobGuard.tryStart(INDEX, "regenerate-search-index"),
        "a thread that could not be created must not leave the index claimed");
  }

  @Test
  public void aSubmittedJobKeepsTheClaimUntilItRuns() throws Exception {
    JobClaim claim = IndexJobGuard.tryStart(INDEX, "regenerate-search-index").orElseThrow();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CommandSearchResource.submitClaimedIndexJob(INDEX, claim, "search index regeneration",
        () -> { }, () -> executor);

    executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(IndexJobGuard.State.COMPLETE, IndexJobGuard.status(INDEX).state(),
        "a job that ran to completion reports complete and leaves the index free");
  }

  @Test
  public void anImportTheExecutorRefusesReleasesTheImport() {
    ValueSetsImportStatusManager imports = ValueSetsImportStatusManager.getInstance();
    JobClaim claim = imports.tryStart().orElseThrow();

    ExecutorService refuses = Executors.newSingleThreadExecutor();
    refuses.shutdown();

    assertThrows(RejectedExecutionException.class,
        () -> CommandSearchResource.submitValueSetsImportJob(claim, () -> { }, () -> refuses));

    assertEquals(ValueSetsImportStatusManager.ImportStatus.ERROR, imports.getImportStatus());
    Optional<JobClaim> next = imports.tryStart();
    assertTrue(next.isPresent(), "an import the executor refused must not stay in progress");
    imports.finish(next.get(), null);
  }

  @Test
  public void aSubmittedImportReportsCompleteOnceItRuns() throws Exception {
    ValueSetsImportStatusManager imports = ValueSetsImportStatusManager.getInstance();
    JobClaim claim = imports.tryStart().orElseThrow();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CommandSearchResource.submitValueSetsImportJob(claim, () -> { }, () -> executor);

    executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(ValueSetsImportStatusManager.ImportStatus.COMPLETE, imports.getImportStatus());
  }

  /**
   * The guard is process-wide, so a claim taken to prove the index was released has to be given back
   * before the next test runs.
   */
  private void releaseWhatTheNextJobCanClaim(Optional<JobClaim> claim, String message) {
    assertTrue(claim.isPresent(), message);
    IndexJobGuard.finish(INDEX, claim.get(), null);
  }
}
