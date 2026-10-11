package edu.campusbuddy.bootstrap;

import edu.campusbuddy.identity.infrastructure.IdentityRepository;
import edu.campusbuddy.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "campus.seed-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DemoData implements ApplicationRunner {
  public static UUID user(int n) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
  }

  private final IdentityRepository identities;
  private final Sql sql;
  private final Clock clock;
  private final Crypto crypto;

  public DemoData(IdentityRepository i, Sql s, Clock c, Crypto x) {
    identities = i;
    sql = s;
    clock = c;
    crypto = x;
  }

  @Transactional
  public void run(ApplicationArguments args) {
    String[] names = {"林同学", "陈同学", "周同学", "许同学", "待认证同学", "平台管理员"};
    for (int i = 0; i < names.length; i++)
      identities.seed(user(i + 1), names[i], i == 5 ? "ADMIN" : "USER", i != 4);
    if (sql.count("select count(*) from activity") > 0) return;
    String[] titles = {"一起自习，把本周任务清零", "傍晚羽毛球，轻松打两局", "午饭搭伙，发现食堂新窗口", "周末校园摄影散步", "读书分享会 · 已结束"};
    String[] types = {"STUDY", "SPORT", "MEAL", "TRAVEL", "OTHER"};
    String[] locations = {"图书馆", "东区体育馆", "第二食堂", "校园北门", "学生活动中心"};
    for (int i = 0; i < 5; i++) {
      UUID id = UUID.nameUUIDFromBytes(("campus-seed-activity-" + i).getBytes());
      Instant start =
          i == 4
              ? clock.instant().minusSeconds(7200)
              : clock.instant().plusSeconds((i + 1) * 86400L);
      Instant end = start.plusSeconds(3600);
      sql.update(
          "insert into"
              + " activity(id,owner_id,title,type,description,campus,tags,start_at,end_at,deadline,confirmation_deadline,capacity,public_location,exact_location_ciphertext,public_lat,public_lon,status,ended_at,terminal_state)"
              + " values(?,?,?,?,?,'主校区','[]',?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          user(1),
          titles[i],
          types[i],
          "一起把小计划变成行动。欢迎愿意守时、互相尊重的同学参加。",
          start,
          end,
          start.minusSeconds(3600),
          start,
          4,
          locations[i],
          crypto.encrypt("示例内部集合点，仅用于虚拟演示"),
          31.2 + i * 0.001,
          121.4 + i * 0.001,
          i == 4 ? "ENDED" : "OPEN",
          i == 4 ? end : null,
          i == 4 ? "ENDED" : null);
      for (int j = 1; j <= (i == 1 || i == 4 ? 2 : 1); j++)
        sql.update(
            "insert into participation(id,activity_id,user_id,role,confirmation) values(?,?,?,?,"
                + " 'CONFIRMED')",
            UUID.randomUUID(),
            id,
            user(j),
            j == 1 ? "OWNER" : "MEMBER");
      if (i == 1 || i == 4)
        sql.update(
            "insert into temporary_group(id,activity_id,access_mode) values(?,?,?)",
            UUID.randomUUID(),
            id,
            i == 4 ? "READ_ONLY" : "ACTIVE");
      if (i == 4)
        sql.update(
            "insert into completion_snapshot(activity_id,user_id,role,ended_at) select"
                + " activity_id,user_id,role,? from participation where activity_id=?",
            end,
            id);
    }
  }
}
