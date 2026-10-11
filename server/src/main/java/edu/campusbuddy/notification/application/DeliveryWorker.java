package edu.campusbuddy.notification.application;

import edu.campusbuddy.notification.api.*;
import edu.campusbuddy.notification.infrastructure.*;
import edu.campusbuddy.shared.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DeliveryWorker {
  private final NotificationRepository repo;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ObjectProvider<PushAdapter> adapters;
  private final Logger log = LoggerFactory.getLogger(getClass());
  private final ThreadPoolExecutor pool =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(20),
          r -> {
            Thread t = new Thread(r, "campus-push");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public DeliveryWorker(
      NotificationRepository r, TransactionTemplate t, Clock c, ObjectProvider<PushAdapter> a) {
    repo = r;
    tx = t;
    clock = c;
    adapters = a;
  }

  private boolean stale(Row task) {
    Row p = new Row((Map<String, Object>) task.get("payload"));
    if (!p.bool("reminder")) return false;
    Row a = repo.activity(p.id("activityId"));
    return a == null
        || a.num("version") != p.num("activityVersion")
        || !a.text("status").equals("OPEN");
  }

  public List<Row> claim() {
    return tx.execute(
        s -> {
          Instant now = clock.instant();
          for (Row expired : repo.expired(now)) {
            repo.expire(expired, now);
            if (expired.num("attempts") >= 30) alert(expired, "delivery_exhausted");
          }
          List<Row> claimed = new ArrayList<>();
          for (Row t : repo.due(now)) {
            if (t.num("schemaVersion") != 1) {
              repo.state(t.id("id"), "QUARANTINED");
              alert(t, "unknown_event_version");
              continue;
            }
            if (stale(t)) {
              repo.state(t.id("id"), "OBSOLETE");
              continue;
            }
            UUID lease = UUID.randomUUID();
            repo.claim(t, lease, now);
            t.put("leaseToken", lease);
            t.put("attempts", t.num("attempts") + 1);
            claimed.add(t);
          }
          return claimed;
        });
  }

  // Revalidate after queueing, immediately before handing data to the channel.
  public boolean beforeSend(Row task) {
    return Boolean.TRUE.equals(
        tx.execute(
            s -> {
              if (!repo.ownsLease(task, clock.instant())) return false;
              if (stale(task)) {
                repo.obsolete(task);
                return false;
              }
              return true;
            }));
  }

  public void finish(Row task, boolean ok, int latency) {
    tx.executeWithoutResult(
        s ->
            repo.finish(
                task,
                ok,
                clock
                    .instant()
                    .plusSeconds(
                        task.num("attempts") == 1 ? 5 : task.num("attempts") == 2 ? 30 : 60),
                latency));
    if (!ok && task.num("attempts") >= 30) alert(task, "delivery_exhausted");
  }

  private void alert(Row t, String event) {
    log.error(
        "event={} taskId={} eventId={} attempt={}",
        event,
        t.get("id"),
        t.get("eventId"),
        t.get("attempts"));
  }

  private record Send(Row task, Future<Boolean> future, long start) {}

  public int runOnce() {
    List<Row> batch = claim();
    List<Send> jobs = new ArrayList<>();
    for (Row task : batch)
      try {
        long begin = System.nanoTime();
        Future<Boolean> f =
            pool.submit(
                () -> {
                  if (!beforeSend(task)) return false;
                  PushAdapter adapter = adapters.getIfAvailable();
                  if (adapter == null) throw new IllegalStateException("CHANNEL_UNAVAILABLE");
                  adapter.send(task);
                  return true;
                });
        jobs.add(new Send(task, f, begin));
      } catch (RejectedExecutionException e) {
        finish(task, false, 0);
      }
    for (Send job : jobs) {
      boolean ok = false;
      try {
        long remaining = TimeUnit.SECONDS.toNanos(3) - (System.nanoTime() - job.start);
        if (remaining <= 0 && !job.future.isDone()) throw new TimeoutException();
        ok = job.future.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        job.future.cancel(true);
      } catch (ExecutionException | TimeoutException e) {
        job.future.cancel(true);
      }
      finish(job.task, ok, (int) ((System.nanoTime() - job.start) / 1_000_000));
    }
    pool.purge();
    return batch.size();
  }

  @PreDestroy
  public void stop() {
    pool.shutdownNow();
  }
}
