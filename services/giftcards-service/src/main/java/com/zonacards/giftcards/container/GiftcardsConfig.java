package com.zonacards.giftcards.container;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/** Settings of the giftcards domain. Typed, with defaults, overridable by environment variables. */
@ConfigMapping(prefix = "zonacards.giftcards")
public interface GiftcardsConfig {

  Codigo codigo();

  Argon2 argon2();

  Generacion generacion();

  interface Codigo {

    /**
     * 32 random bytes, Base64. Encrypts card codes. A secret. Optional here only because the
     * migration task starts without it; serving without it fails when the first card is made.
     */
    Optional<String> key();

    /** 32 other random bytes, Base64. Keys the blind index of card codes. A secret. */
    Optional<String> hmacKey();
  }

  /** Cost of the CVV hash. The defaults are the OWASP minimum for Argon2id. */
  interface Argon2 {

    @WithDefault("19456")
    int memoryKib();

    @WithDefault("2")
    int iterations();

    @WithDefault("1")
    int parallelism();
  }

  interface Generacion {

    /** Cards per fragment: the unit of work, of retry and of progress. */
    @WithDefault("1000")
    int fragmentSize();

    /** Fragments in flight per task. More than the task has cores only makes each one slower. */
    @WithDefault("2")
    int concurrency();

    /** Threads of the compute pool; 0 means one per available processor. */
    @WithDefault("0")
    int cpuThreads();

    /** Must match the visibility timeout of the work queue. It is extended while a fragment runs. */
    @WithDefault("PT5M")
    Duration visibilityTimeout();
  }
}
