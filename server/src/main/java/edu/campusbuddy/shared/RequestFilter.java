package edu.campusbuddy.shared;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(-110)
public class RequestFilter extends OncePerRequestFilter {
  private final Json json;
  private final Logger log = LoggerFactory.getLogger(getClass());
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  private record Window(long minute, AtomicInteger count) {}

  public RequestFilter(Json j) {
    json = j;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    MDC.put("requestId", id);
    res.setHeader("X-Request-ID", id);
    res.setHeader("Cache-Control", "no-store");
    long start = System.nanoTime();
    try {
      boolean write = !List.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
      boolean upload = isMediaUpload(req);
      if (req.getRequestURI().startsWith("/api/") && write) {
        // JSON business writes are capped at 64 KiB. The image endpoint is the one
        // intentional exception: it is multipart and enforces its own 5 MiB limit.
        if (!upload) {
          String ct = req.getContentType();
          if (ct == null || !ct.toLowerCase().startsWith("application/json")) {
            reject(res, 415, "CONTENT_TYPE_REQUIRED", "写操作需要application/json", id);
            return;
          }
          if (req.getContentLengthLong() > 65536) {
            reject(res, 413, "PAYLOAD_TOO_LARGE", "请求超过64KB", id);
            return;
          }
        }
        String client =
            req.getSession(false) == null ? req.getRemoteAddr() : req.getSession(false).getId();
        long minute = System.currentTimeMillis() / 60000;
        Window w =
            windows.compute(
                client,
                (k, v) ->
                    v == null || v.minute != minute ? new Window(minute, new AtomicInteger()) : v);
        if (w.count.incrementAndGet() > 180) {
          res.setHeader("Retry-After", "60");
          reject(res, 429, "RATE_LIMITED", "操作过于频繁，请稍后再试", id);
          return;
        }
        if (windows.size() > 10000) windows.entrySet().removeIf(e -> e.getValue().minute < minute);
      }
      if (req.getRequestURI().startsWith("/api/") && write && !upload) {
        byte[] bytes = req.getInputStream().readNBytes(65537);
        if (bytes.length > 65536) {
          reject(res, 413, "PAYLOAD_TOO_LARGE", "请求超过64KB", id);
          return;
        }
        HttpServletRequest wrapped =
            new HttpServletRequestWrapper(req) {
              public ServletInputStream getInputStream() {
                java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                  public int read() {
                    return input.read();
                  }

                  public boolean isFinished() {
                    return input.available() == 0;
                  }

                  public boolean isReady() {
                    return true;
                  }

                  public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException("Synchronous request body");
                  }
                };
              }

              public java.io.BufferedReader getReader() {
                return new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
              }
            };
        chain.doFilter(wrapped, res);
      } else chain.doFilter(req, res);
    } finally {
      if (req.getRequestURI().startsWith("/api/"))
        log.info(
            "requestId={} method={} path={} status={} elapsedMs={}",
            id,
            req.getMethod(),
            req.getRequestURI(),
            res.getStatus(),
            (System.nanoTime() - start) / 1000000);
      MDC.remove("requestId");
    }
  }

  private boolean isMediaUpload(HttpServletRequest req) {
    return "POST".equals(req.getMethod()) && "/api/v1/media/upload".equals(req.getRequestURI());
  }

  private void reject(HttpServletResponse res, int status, String code, String message, String id)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(json.write(Row.of("code", code, "message", message, "requestId", id)));
  }
}
