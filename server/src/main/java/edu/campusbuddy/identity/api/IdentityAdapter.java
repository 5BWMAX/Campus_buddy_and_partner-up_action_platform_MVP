package edu.campusbuddy.identity.api;

import edu.campusbuddy.shared.Row;
import java.util.List;
import java.util.UUID;

public interface IdentityAdapter {
  Row authenticate(UUID subject);

  List<Row> accounts();
}
