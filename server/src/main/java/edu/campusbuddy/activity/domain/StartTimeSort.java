package edu.campusbuddy.activity.domain;

import org.springframework.stereotype.Component;

@Component
public class StartTimeSort implements ActivitySort {
  public String name() {
    return "START_TIME";
  }

  public String column() {
    return "start_at";
  }
}
