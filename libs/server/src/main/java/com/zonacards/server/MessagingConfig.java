package com.zonacards.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Map;

/**
 * Messaging settings of a service. Everything can be overridden with environment variables, and
 * both loops can be switched off, which is how one image can later be deployed as separate
 * "api" and "worker" services without code changes.
 */
@ConfigMapping(prefix = "zonacards.messaging")
public interface MessagingConfig {

  /** Logical topic name to SNS topic ARN. */
  Map<String, String> topics();

  /** Logical queue name to SQS queue URL. */
  Map<String, String> queues();

  Relay relay();

  Consumer consumer();

  /** Time given to consumers and relay to finish on shutdown. Keep it below the ECS stopTimeout. */
  @WithDefault("PT60S")
  Duration shutdownTimeout();

  /** A loop silent for longer than this makes the liveness check fail. */
  @WithDefault("PT90S")
  Duration healthMaxSilence();

  interface Relay {

    @WithDefault("true")
    boolean enabled();

    @WithDefault("50")
    int batchSize();

    @WithDefault("PT0.25S")
    Duration pollInterval();

    @WithDefault("PT30S")
    Duration lease();
  }

  interface Consumer {

    @WithDefault("true")
    boolean enabled();

    /** Messages in flight per queue in this task. Size it against the database pool. */
    @WithDefault("4")
    int concurrency();

    @WithDefault("PT20S")
    Duration waitTime();

    @WithDefault("PT60S")
    Duration visibilityTimeout();
  }
}
