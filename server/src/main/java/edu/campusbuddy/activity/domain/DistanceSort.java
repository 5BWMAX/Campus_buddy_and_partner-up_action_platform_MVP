package edu.campusbuddy.activity.domain;

import org.springframework.stereotype.Component;

@Component
public class DistanceSort implements ActivitySort {
  public String name() {
    return "DISTANCE";
  }

  public String column() {
    return "distance";
  }
}
