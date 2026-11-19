package com.zonacards.eventx;

/** A failure of the messaging machinery itself (malformed message, interrupted wait). */
public class EventxException extends RuntimeException {

  public EventxException(String message) {
    super(message);
  }

  public EventxException(String message, Throwable cause) {
    super(message, cause);
  }
}
