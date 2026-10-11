package edu.campusbuddy.notification.api;

import edu.campusbuddy.shared.Row;
import java.util.UUID;

public interface NotificationApi {
  void record(UUID recipient, UUID activity, String type, String eventKey, Row payload);
}
