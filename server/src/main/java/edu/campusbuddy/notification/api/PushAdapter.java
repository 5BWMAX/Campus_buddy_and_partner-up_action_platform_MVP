package edu.campusbuddy.notification.api;

import edu.campusbuddy.shared.Row;

public interface PushAdapter {
  void send(Row task);
}
