package edu.campusbuddy.activity.infrastructure;

import edu.campusbuddy.shared.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class SearchHistoryRepository {
  private final Sql sql;

  public SearchHistoryRepository(Sql s) {
    sql = s;
  }

  public void save(UUID userId, String keyword) {
    // 清除相同关键词的旧记录，保留最新
    sql.update("delete from search_history where user_id=? and keyword=?", userId, keyword);
    sql.update(
        "insert into search_history(id, user_id, keyword) values(?, ?, ?)",
        UUID.randomUUID(),
        userId,
        keyword);
    // 只保留最近 20 条
    sql.update(
        "delete from search_history where user_id=? and id not in (select id from search_history where user_id=? order by created_at desc limit 20)",
        userId,
        userId);
  }

  public List<Row> list(UUID userId, int limit) {
    return sql.list(
        "select keyword, created_at from search_history where user_id=? order by created_at desc limit ?",
        userId,
        limit);
  }

  public void clear(UUID userId) {
    sql.update("delete from search_history where user_id=?", userId);
  }

  public void delete(UUID userId, String keyword) {
    sql.update("delete from search_history where user_id=? and keyword=?", userId, keyword);
  }
}
