package edu.campusbuddy.shared;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class Idempotency {
  private final Sql sql;
  private final Json json;

  public Idempotency(Sql s, Json j) {
    sql = s;
    json = j;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Row run(
      UUID actor, String operation, UUID key, Object body, int status, Supplier<Row> action) {
    String hash = json.hash(body);
    int n =
        sql.update(
            "insert into idempotency_record(actor_id,operation,key,request_hash) values(?,?,?,?) on"
                + " conflict do nothing",
            actor,
            operation,
            key,
            hash);
    if (n == 0) {
      Row old =
          sql.must(
              "select * from idempotency_record where actor_id=? and operation=? and key=?",
              actor,
              operation,
              key);
      Problem.require(hash.equals(old.text("requestHash")), 409, "KEY_REUSED", "同一幂等键不能用于不同内容");
      return new Row((Map<String, Object>) old.get("responseJson"));
    }
    Row result = action.get();
    sql.update(
        "update idempotency_record set response_json=?::jsonb,http_status=? where actor_id=? and"
            + " operation=? and key=?",
        json.write(result),
        status,
        actor,
        operation,
        key);
    return result;
  }
}
