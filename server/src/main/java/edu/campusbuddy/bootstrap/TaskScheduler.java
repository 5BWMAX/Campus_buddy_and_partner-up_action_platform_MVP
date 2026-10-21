package edu.campusbuddy.bootstrap;

import edu.campusbuddy.activity.api.ActivityApi;
import edu.campusbuddy.notification.application.DeliveryWorker;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component("campusEventScheduler")
public class TaskScheduler {
  private final ActivityApi activity;
  private final DeliveryWorker worker;
  private final edu.campusbuddy.governance.api.GovernanceApi governance;
  private final boolean enabled;
  private final boolean externalPushEnabled;
  private final Logger log = LoggerFactory.getLogger(getClass());

  public TaskScheduler(
      ActivityApi a,
      DeliveryWorker w,
      edu.campusbuddy.governance.api.GovernanceApi g,
      @Value("${campus.scheduler-enabled:true}") boolean e,
      @Value("${campus.external-push-enabled:true}") boolean externalPushEnabled) {
    activity = a;
    governance = g;
    worker = w;
    enabled = e;
    this.externalPushEnabled = externalPushEnabled;
  }

  @Scheduled(fixedDelay = 5000)
  public void scan() {
    if (!enabled) return;
    for (var id : activity.due())
      try {
        activity.advance(id);
      } catch (Exception e) {
        log.error("activityTask={} errorType={}", id, e.getClass().getName());
      }
  }

  @Scheduled(fixedDelay = 5000)
  public void governanceSla() {
    if (!enabled) return;
    try {
      governance.scanOverdue();
    } catch (Exception e) {
      log.error("governanceSlaTask errorType={}", e.getClass().getName());
    }
  }

  @Scheduled(fixedDelay = 5000)
  public void deliver() {
    if (!enabled || !externalPushEnabled) return;
    try {
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      for (int batch = 0; batch < 100 && System.nanoTime() < until; batch++)
        if (worker.runOnce() == 0) break;
    } catch (Exception e) {
      log.error("deliveryTask errorType={}", e.getClass().getName());
    }
  }
}
