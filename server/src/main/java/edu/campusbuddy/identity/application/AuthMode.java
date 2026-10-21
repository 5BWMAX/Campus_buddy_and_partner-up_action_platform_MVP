package edu.campusbuddy.identity.application;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class AuthMode {
  private final boolean formal;

  public AuthMode(Environment environment) {
    formal = environment.acceptsProfiles(Profiles.of("formal"));
    if (formal && environment.acceptsProfiles(Profiles.of("demo")))
      throw new IllegalStateException("formal and demo profiles cannot be enabled together");
  }

  public boolean formal() {
    return formal;
  }
}
