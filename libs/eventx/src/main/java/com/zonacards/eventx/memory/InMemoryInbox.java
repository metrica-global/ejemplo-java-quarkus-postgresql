package com.zonacards.eventx.memory;

import com.zonacards.eventx.Inbox;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryInbox implements Inbox {

  private final Set<String> seen = ConcurrentHashMap.newKeySet();

  @Override
  public boolean firstTime(String consumer, String messageId) {
    String key = consumer + "|" + messageId;
    if (!seen.add(key)) {
      return false;
    }
    InMemoryUnitOfWork.onRollback(() -> seen.remove(key));
    return true;
  }

  @Override
  public int purge(Duration olderThan, int limit) {
    return 0;
  }

  /** Simulates the purge of old records, to test handlers that must not rely on the inbox alone. */
  public void forgetEverything() {
    seen.clear();
  }
}
