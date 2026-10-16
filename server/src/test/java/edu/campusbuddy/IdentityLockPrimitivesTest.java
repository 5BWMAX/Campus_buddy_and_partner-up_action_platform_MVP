package edu.campusbuddy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campusbuddy.identity.infrastructure.IdentityRepository;
import edu.campusbuddy.shared.Problem;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Execution(ExecutionMode.SAME_THREAD)
class IdentityLockPrimitivesTest extends PgIntegrationSupport {
  private static final int FUTURE_TIMEOUT_SECONDS = 20;
  private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(4);

  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean IdentityRepository lockRepository;

  @AfterEach
  void noConnectionRemainsIdleInTransaction() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from pg_stat_activity where datname=current_database() and"
                + " usename=current_user and state='idle in transaction'",
            Integer.class);
    assertEquals(0, Objects.requireNonNull(count));
  }

  @Test
  void keyShareLocksOnTheSameUserAreCompatible() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstHolding = new CountDownLatch(1),
        secondHolding = new CountDownLatch(1),
        release = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger(), secondPid = new AtomicInteger();
    Future<Void> first = null, second = null;
    try {
      first =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        firstPid.set(backendPid());
                        identity.lockUserReferences(List.of(BOB));
                        firstHolding.countDown();
                        await(release, "release first reference lock");
                        return null;
                      }));
      await(firstHolding, "first transaction to hold KEY SHARE");

      second =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        secondPid.set(backendPid());
                        identity.lockUserReferences(List.of(BOB));
                        secondHolding.countDown();
                        await(release, "release second reference lock");
                        return null;
                      }));
      await(secondHolding, "second transaction to acquire compatible KEY SHARE");

      assertNotEquals(firstPid.get(), secondPid.get());
      assertNoBlockers(firstPid.get());
      assertNoBlockers(secondPid.get());
      release.countDown();
      first.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      second.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertTransactionClean(firstPid.get());
      assertTransactionClean(secondPid.get());
      System.out.printf(
          Locale.ROOT,
          "A0 compatible-key-share database=%s firstPid=%d secondPid=%d blockers=none%n",
          database(),
          firstPid.get(),
          secondPid.get());
    } finally {
      release.countDown();
      cancelIfRunning(first);
      cancelIfRunning(second);
      closePool(pool);
    }
  }

  @Test
  void keyShareBlocksExistingForUpdateUntilReferenceTransactionCommits() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch referenceHolding = new CountDownLatch(1),
        updateStarted = new CountDownLatch(1),
        updateAcquired = new CountDownLatch(1),
        releaseReference = new CountDownLatch(1);
    AtomicInteger referencePid = new AtomicInteger(), updatePid = new AtomicInteger();
    Future<Void> reference = null, update = null;
    try {
      reference =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        referencePid.set(backendPid());
                        identity.lockUserReferences(List.of(BOB));
                        referenceHolding.countDown();
                        await(releaseReference, "release reference lock");
                        return null;
                      }));
      await(referenceHolding, "reference transaction to hold KEY SHARE");

      update =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        updatePid.set(backendPid());
                        updateStarted.countDown();
                        identity.lockUsers(List.of(BOB));
                        updateAcquired.countDown();
                        return null;
                      }));
      await(updateStarted, "FOR UPDATE transaction to start");
      awaitBlockedBy(updatePid.get(), referencePid.get());

      releaseReference.countDown();
      reference.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      update.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertEquals(0, updateAcquired.getCount());
      assertTransactionClean(referencePid.get());
      assertTransactionClean(updatePid.get());
      System.out.printf(
          Locale.ROOT,
          "A0 key-share-update-conflict database=%s referencePid=%d blockedUpdatePid=%d%n",
          database(),
          referencePid.get(),
          updatePid.get());
    } finally {
      releaseReference.countDown();
      cancelIfRunning(reference);
      cancelIfRunning(update);
      closePool(pool);
    }
  }

  @Test
  void mixedLocksUseOneUuidOrderRegardlessOfLockMode() throws Exception {
    assertMixedOrder(List.of(BOB), List.of(ALICE), LockMode.REFERENCE);
    assertMixedOrder(List.of(ALICE), List.of(BOB), LockMode.UPDATE);
  }

  @Test
  void duplicateAcrossBothCollectionsUsesOnlyForUpdate() throws Exception {
    clearInvocations(lockRepository);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch referenceHolding = new CountDownLatch(1),
        mixedStarted = new CountDownLatch(1),
        releaseReference = new CountDownLatch(1);
    AtomicInteger referencePid = new AtomicInteger(), mixedPid = new AtomicInteger();
    Future<Void> reference = null, mixed = null;
    try {
      reference =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        referencePid.set(backendPid());
                        identity.lockUserReferences(List.of(BOB));
                        referenceHolding.countDown();
                        await(releaseReference, "release overlap reference lock");
                        return null;
                      }));
      await(referenceHolding, "reference transaction to hold overlap user");

      mixed =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        mixedPid.set(backendPid());
                        mixedStarted.countDown();
                        identity.lockUsersAndReferences(
                            List.of(BOB, BOB), List.of(BOB, BOB));
                        return null;
                      }));
      await(mixedStarted, "overlapping mixed lock transaction to start");
      awaitBlockedBy(mixedPid.get(), referencePid.get());

      releaseReference.countDown();
      reference.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      mixed.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      verify(lockRepository, times(1)).lock(BOB);
      verify(lockRepository, times(1)).lockReference(BOB);
      assertTransactionClean(referencePid.get());
      assertTransactionClean(mixedPid.get());
      System.out.printf(
          Locale.ROOT,
          "A0 overlapping-lock-strength database=%s referencePid=%d blockedMixedPid=%d"
              + " selected=FOR_UPDATE%n",
          database(),
          referencePid.get(),
          mixedPid.get());
    } finally {
      releaseReference.countDown();
      cancelIfRunning(reference);
      cancelIfRunning(mixed);
      closePool(pool);
    }
  }

  @Test
  void missingUsersKeepExistingResourceNotFoundSemantics() {
    UUID missing = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    Problem existing = missing(() -> identity.lockUsers(List.of(missing)));
    Problem reference = missing(() -> identity.lockUserReferences(List.of(missing)));
    Problem mixedUpdate =
        missing(() -> identity.lockUsersAndReferences(List.of(missing), List.of()));
    Problem mixedReference =
        missing(() -> identity.lockUsersAndReferences(List.of(), List.of(missing)));

    assertEquals(404, existing.status);
    assertEquals("RESOURCE_NOT_FOUND", existing.code);
    assertEquals("资源不存在或无权访问", existing.getMessage());
    for (Problem actual : List.of(reference, mixedUpdate, mixedReference)) {
      assertEquals(existing.status, actual.status);
      assertEquals(existing.code, actual.code);
      assertEquals(existing.getMessage(), actual.getMessage());
    }
  }

  @Test
  void newLockApisRequireAnExistingTransaction() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> identity.lockUserReferences(List.of(ALICE)));
    assertThrows(
        IllegalTransactionStateException.class,
        () -> identity.lockUsersAndReferences(List.of(ALICE), List.of(BOB)));
  }

  private void assertMixedOrder(
      Collection<UUID> updateIds,
      Collection<UUID> referenceIds,
      LockMode lowerExpectedMode)
      throws Exception {
    UUID lower = ALICE, higher = BOB;
    LockMode blockingMode =
        lowerExpectedMode == LockMode.UPDATE ? LockMode.REFERENCE : LockMode.UPDATE;
    ExecutorService pool = Executors.newFixedThreadPool(3);
    CountDownLatch blockerHolding = new CountDownLatch(1),
        mixedStarted = new CountDownLatch(1),
        probeHolding = new CountDownLatch(1),
        releaseBlocker = new CountDownLatch(1),
        releaseProbe = new CountDownLatch(1);
    AtomicInteger blockerPid = new AtomicInteger(),
        mixedPid = new AtomicInteger(),
        probePid = new AtomicInteger();
    Future<Void> blocker = null, mixed = null, probe = null;
    try {
      blocker =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        blockerPid.set(backendPid());
                        acquire(blockingMode, lower);
                        blockerHolding.countDown();
                        await(releaseBlocker, "release lower UUID blocker");
                        return null;
                      }));
      await(blockerHolding, "lower UUID blocker to acquire its lock");

      mixed =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        mixedPid.set(backendPid());
                        mixedStarted.countDown();
                        identity.lockUsersAndReferences(updateIds, referenceIds);
                        return null;
                      }));
      await(mixedStarted, "mixed lock transaction to start");
      awaitBlockedBy(mixedPid.get(), blockerPid.get());

      probe =
          pool.submit(
              () ->
                  transaction(
                      () -> {
                        configureTimeouts();
                        probePid.set(backendPid());
                        identity.lockUsers(List.of(higher));
                        probeHolding.countDown();
                        await(releaseProbe, "release higher UUID probe");
                        return null;
                      }));
      await(probeHolding, OBSERVATION_TIMEOUT, "higher UUID to remain unlocked");
      assertNoBlockers(probePid.get());

      releaseProbe.countDown();
      probe.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      releaseBlocker.countDown();
      blocker.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      mixed.get(FUTURE_TIMEOUT_SECONDS, SECONDS);
      assertTransactionClean(blockerPid.get());
      assertTransactionClean(mixedPid.get());
      assertTransactionClean(probePid.get());
      System.out.printf(
          Locale.ROOT,
          "A0 mixed-order database=%s lowerMode=%s blockerPid=%d blockedMixedPid=%d"
              + " freeHigherProbePid=%d%n",
          database(),
          lowerExpectedMode,
          blockerPid.get(),
          mixedPid.get(),
          probePid.get());
    } finally {
      releaseProbe.countDown();
      releaseBlocker.countDown();
      cancelIfRunning(blocker);
      cancelIfRunning(mixed);
      cancelIfRunning(probe);
      closePool(pool);
    }
  }

  private Problem missing(Runnable action) {
    return assertThrows(
        Problem.class,
        () ->
            transaction(
                () -> {
                  action.run();
                  return null;
                }));
  }

  private void acquire(LockMode mode, UUID user) {
    if (mode == LockMode.UPDATE) identity.lockUsers(List.of(user));
    else identity.lockUserReferences(List.of(user));
  }

  private <T> T transaction(Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(status -> action.get());
  }

  private void configureTimeouts() {
    jdbc.execute("set local lock_timeout = '12s'");
    jdbc.execute("set local statement_timeout = '15s'");
  }

  private String database() {
    return Objects.requireNonNull(jdbc.queryForObject("select current_database()", String.class));
  }

  private int backendPid() {
    return Objects.requireNonNull(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
  }

  private void awaitBlockedBy(int waiterPid, int blockerPid) {
    long deadline = System.nanoTime() + OBSERVATION_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (isBlockedBy(waiterPid, blockerPid)) return;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
    fail("backend " + waiterPid + " was not observed waiting for backend " + blockerPid);
  }

  private boolean isBlockedBy(int waiterPid, int blockerPid) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select ? = any(pg_blocking_pids(?))", Boolean.class, blockerPid, waiterPid));
  }

  private void assertNoBlockers(int backendPid) {
    Integer count =
        jdbc.queryForObject("select cardinality(pg_blocking_pids(?))", Integer.class, backendPid);
    assertEquals(0, Objects.requireNonNull(count));
  }

  private void assertTransactionClean(int backendPid) {
    assertNoBlockers(backendPid);
    Boolean clean =
        jdbc.queryForObject(
            "select coalesce((select state <> 'idle in transaction' from pg_stat_activity where"
                + " pid=?), true)",
            Boolean.class,
            backendPid);
    assertTrue(Boolean.TRUE.equals(clean));
  }

  private void await(CountDownLatch latch, String description) {
    await(latch, Duration.ofSeconds(FUTURE_TIMEOUT_SECONDS), description);
  }

  private void await(CountDownLatch latch, Duration timeout, String description) {
    try {
      assertTrue(
          latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS),
          "timed out waiting to " + description);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting to " + description, error);
    }
  }

  private void cancelIfRunning(Future<?> future) {
    if (future != null && !future.isDone()) future.cancel(true);
  }

  private void closePool(ExecutorService pool) {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS)) {
        pool.shutdownNow();
        assertTrue(
            pool.awaitTermination(FUTURE_TIMEOUT_SECONDS, SECONDS),
            "A0 concurrency test worker pool did not terminate");
      }
    } catch (InterruptedException error) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while closing A0 worker pool", error);
    }
  }

  private enum LockMode {
    UPDATE,
    REFERENCE
  }
}
