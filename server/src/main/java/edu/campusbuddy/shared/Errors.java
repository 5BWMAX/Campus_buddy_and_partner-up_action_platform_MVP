package edu.campusbuddy.shared;

import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {
  private final Logger log = LoggerFactory.getLogger(getClass());

  @ExceptionHandler(Problem.class)
  public ResponseEntity<Row> problem(Problem e) {
    return ResponseEntity.status(e.status)
        .body(Row.of("code", e.code, "message", e.getMessage(), "requestId", Trace.id()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Row> input(Exception e) {
    return problem(new Problem(400, "VALIDATION_FAILED", "请求内容格式不正确"));
  }

  @ExceptionHandler({
    CannotAcquireLockException.class,
    QueryTimeoutException.class,
    TransientDataAccessException.class
  })
  public ResponseEntity<Row> transientError(Exception e) {
    return problem(new Problem(503, "TEMPORARILY_UNAVAILABLE", "数据库繁忙，请保留原请求标识重试"));
  }

  @ExceptionHandler(UncategorizedSQLException.class)
  public ResponseEntity<Row> uncategorized(UncategorizedSQLException e) {
    if (e.getSQLException() != null && "55P03".equals(e.getSQLException().getSQLState()))
      return transientError(e);
    return other(e);
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  public ResponseEntity<Row> missing(Exception e) {
    return problem(Problem.missing());
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Row> method(Exception e) {
    return problem(new Problem(405, "METHOD_NOT_ALLOWED", "请求方法不支持"));
  }

  @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
  public ResponseEntity<Row> missingParameter(Exception e) {
    return input(e);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Row> other(Exception e) {
    log.error("requestId={} unhandledType={}", Trace.id(), e.getClass().getName());
    return problem(new Problem(500, "INTERNAL_ERROR", "服务出现异常，请凭请求编号联系维护人员"));
  }
}
