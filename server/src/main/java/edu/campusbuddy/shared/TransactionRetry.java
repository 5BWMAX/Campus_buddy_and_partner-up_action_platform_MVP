package edu.campusbuddy.shared;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs outside Spring's transaction interceptor; every retry opens a fresh transaction. */
@Aspect
@Component
@Order(10)
public class TransactionRetry {
  private final Logger log = LoggerFactory.getLogger(getClass());

  @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
  public Object around(ProceedingJoinPoint point) throws Throwable {
    if (TransactionSynchronizationManager.isActualTransactionActive()) return point.proceed();
    for (int attempt = 0; ; attempt++)
      try {
        return point.proceed();
      } catch (Throwable e) {
        if (attempt >= 2 || !retryable(e)) throw e;
        log.warn("requestId={} event=transaction_retry attempt={}", Trace.id(), attempt + 1);
        LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(10_000_000, 35_000_000));
      }
  }

  private boolean retryable(Throwable e) {
    if (e instanceof Problem p) return p.code.equals("TEMPORARILY_UNAVAILABLE");
    for (Throwable t = e; t != null; t = t.getCause())
      if (t instanceof SQLException s
          && ("40001".equals(s.getSQLState()) || "40P01".equals(s.getSQLState()))) return true;
    return false;
  }
}
