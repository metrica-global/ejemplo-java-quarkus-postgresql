package com.zonacards.kernel;

/**
 * Who is acting: a person, a machine client (OAuth client credentials) or the system itself when
 * it reacts to an event. Exactly one kind; an actor with no id is invalid and must be rejected at
 * the authentication boundary.
 */
public record Actor(Kind kind, String id) {

  public enum Kind {
    USER,
    CLIENT,
    SYSTEM
  }

  public static Actor user(UserId id) {
    return new Actor(Kind.USER, id == null ? "" : id.value());
  }

  public static Actor client(ClientId id) {
    return new Actor(Kind.CLIENT, id == null ? "" : id.value());
  }

  public static Actor system(String name) {
    return new Actor(Kind.SYSTEM, name == null ? "" : name);
  }

  public boolean isValid() {
    return kind != null && id != null && !id.isBlank();
  }

  public boolean isUser() {
    return kind == Kind.USER;
  }

  public boolean isClient() {
    return kind == Kind.CLIENT;
  }

  public boolean isSystem() {
    return kind == Kind.SYSTEM;
  }
}
