package edu.campusbuddy.notification.infrastructure;

import edu.campusbuddy.notification.api.PushAdapter;
import edu.campusbuddy.shared.Row;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class MockPushAdapter implements PushAdapter {
  private final AtomicBoolean unavailable;
  private volatile long delayMillis;

  public void setDelayMillis(long value) {
    delayMillis = value;
  }

  public MockPushAdapter(@Value("${campus.mock-push-fail:false}") boolean fail) {
    unavailable = new AtomicBoolean(fail);
  }

  public void setUnavailable(boolean value) {
    unavailable.set(value);
  }

  public void send(Row task) {
    if (delayMillis > 0)
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("INTERRUPTED");
      }
    if (unavailable.get()) throw new IllegalStateException("MOCK_UNAVAILABLE");
  }
}
