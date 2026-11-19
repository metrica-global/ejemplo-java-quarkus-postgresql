package com.zonacards.eventx.aws;

import com.zonacards.eventx.EventxException;
import java.util.Map;

/**
 * Maps the logical names the code uses ({@code lotes-events}) to what AWS needs (a topic ARN, a
 * queue URL). The mapping comes from configuration that Terraform outputs; nothing is looked up
 * or created at runtime, so a service can only reach what it was explicitly given.
 */
public final class Destinations {

  /** Placeholder used by the configuration when a variable was not provided. */
  public static final String UNSET = "unset";

  private final Map<String, String> topicArns;
  private final Map<String, String> queueUrls;

  public Destinations(Map<String, String> topicArns, Map<String, String> queueUrls) {
    this.topicArns = Map.copyOf(topicArns);
    this.queueUrls = Map.copyOf(queueUrls);
  }

  public String topicArn(String topic) {
    String arn = topicArns.get(topic);
    if (arn == null || arn.isBlank() || UNSET.equals(arn)) {
      throw new EventxException("no ARN configured for topic '" + topic + "'");
    }
    return arn;
  }

  public String queueUrl(String queue) {
    String url = queueUrls.get(queue);
    if (url == null || url.isBlank() || UNSET.equals(url)) {
      throw new EventxException("no URL configured for queue '" + queue + "'");
    }
    return url;
  }
}
