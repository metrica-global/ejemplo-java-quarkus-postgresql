package com.zonacards.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The event contract, as example documents in {@code docs/eventos/ejemplos}. One file per event
 * type. The two sides are tested against the same file and share no code:
 *
 * <ul>
 *   <li>the producer's test asserts that what it really emits has the shape of the example;
 *   <li>the consumer's test feeds the example to its handler.
 * </ul>
 *
 * If a producer changes a field, its own build fails here, before any consumer is affected.
 */
public final class ContractExamples {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DIRECTORY = "docs/eventos/ejemplos/";
  /** Attributes a producer may omit. */
  private static final Set<String> OPTIONAL_ATTRIBUTES = Set.of("traceparent");

  private ContractExamples() {}

  public static ObjectNode load(String type) {
    try {
      ObjectNode example = (ObjectNode) MAPPER.readTree(RepoFiles.read(DIRECTORY + type + ".json"));
      if (!type.equals(example.path("type").asText())) {
        throw new AssertionError("the example file of " + type + " declares type " + example.path("type"));
      }
      return example;
    } catch (JsonProcessingException e) {
      throw new AssertionError("the example of " + type + " is not valid JSON", e);
    }
  }

  /**
   * A new event built from the example: fresh id, the given tenant, and the given values replacing
   * fields of {@code data}. This is what a consumer test delivers to its handler.
   */
  public static String instance(String type, String tenantId, Map<String, Object> data) {
    ObjectNode event = load(type);
    event.put("id", UUID.randomUUID().toString());
    event.put("tenantid", tenantId);
    ObjectNode payload = (ObjectNode) event.get("data");
    for (Map.Entry<String, Object> field : data.entrySet()) {
      if (!payload.has(field.getKey())) {
        throw new AssertionError(type + " has no field '" + field.getKey() + "' in its contract");
      }
      payload.set(field.getKey(), MAPPER.valueToTree(field.getValue()));
    }
    if (data.containsKey("loteId")) {
      event.put("subject", String.valueOf(data.get("loteId")));
    }
    return event.toString();
  }

  /** Fails unless the produced event has exactly the attributes and data fields of the example. */
  public static void assertMatches(String type, JsonNode produced) {
    ObjectNode example = load(type);
    Set<String> expected = names(example);
    Set<String> actual = names(produced);
    Set<String> missing = new TreeSet<>(expected);
    missing.removeAll(actual);
    missing.removeAll(OPTIONAL_ATTRIBUTES);
    Set<String> unexpected = new TreeSet<>(actual);
    unexpected.removeAll(expected);
    if (!missing.isEmpty() || !unexpected.isEmpty()) {
      throw new AssertionError(type + ": envelope differs from the contract; missing " + missing + ", undocumented " + unexpected);
    }
    for (String name : expected) {
      if (!name.equals("data") && produced.has(name)) {
        sameKind(type, name, example.get(name), produced.get(name));
      }
    }
    sameShape(type, "data", example.get("data"), produced.get("data"));
  }

  private static void sameShape(String type, String path, JsonNode expected, JsonNode actual) {
    sameKind(type, path, expected, actual);
    if (!expected.isObject()) {
      return;
    }
    Set<String> expectedNames = names(expected);
    Set<String> actualNames = names(actual);
    if (!expectedNames.equals(actualNames)) {
      throw new AssertionError(type + ": fields of " + path + " are " + actualNames + " but the contract says " + expectedNames);
    }
    for (String name : expectedNames) {
      sameShape(type, path + "." + name, expected.get(name), actual.get(name));
    }
  }

  private static void sameKind(String type, String path, JsonNode expected, JsonNode actual) {
    if (expected.getNodeType() != actual.getNodeType()) {
      throw new AssertionError(
          type + ": " + path + " is " + actual.getNodeType() + " but the contract says " + expected.getNodeType());
    }
  }

  private static Set<String> names(JsonNode node) {
    Set<String> names = new TreeSet<>();
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      names.add(it.next());
    }
    return names;
  }
}
