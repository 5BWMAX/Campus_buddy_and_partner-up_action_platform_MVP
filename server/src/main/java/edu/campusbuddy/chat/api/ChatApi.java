package edu.campusbuddy.chat.api;

import java.util.UUID;

public interface ChatApi {
  UUID requireReportable(UUID actor, UUID message);

  UUID author(UUID message);

  void hide(UUID message, boolean hidden);
}
