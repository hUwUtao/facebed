package com.facebed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class JsonSearchService {
  private final ObjectMapper mapper;

  public JsonSearchService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public List<JsonNode> jsonBlocks(Document document) {
    List<JsonNode> blocks = new ArrayList<>();
    for (Element script : document.select("script[type=application/json]")) {
      String json = script.text();
      if (json == null || json.isBlank()) {
        continue;
      }
      try {
        blocks.add(mapper.readTree(json));
      } catch (Exception ignored) {
        // Facebook ships some garbage JSON-ish blobs. We just skip them.
      }
    }
    return blocks;
  }

  public JsonNode first(JsonNode node, String key) {
    List<JsonNode> found = all(node, key);
    return found.isEmpty() ? null : found.get(0);
  }

  public List<JsonNode> all(JsonNode node, String key) {
    if (node == null || key == null || key.isBlank()) {
      return Collections.emptyList();
    }
    List<JsonNode> found = new ArrayList<>();
    walk(node, key, found);
    return found;
  }

  public boolean has(JsonNode node, String... keys) {
    if (node == null || keys == null) {
      return false;
    }
    for (String key : keys) {
      if (first(node, key) == null) {
        return false;
      }
    }
    return true;
  }

  public JsonNode path(JsonNode node, String... fields) {
    JsonNode cursor = node;
    if (cursor == null) {
      return null;
    }
    for (String field : fields) {
      if (cursor == null || !cursor.isObject()) {
        return null;
      }
      cursor = cursor.get(field);
    }
    return cursor;
  }

  public String text(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  private void walk(JsonNode node, String key, List<JsonNode> found) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      node.fields().forEachRemaining(entry -> {
        if (key.equals(entry.getKey())) {
          found.add(entry.getValue());
        }
        walk(entry.getValue(), key, found);
      });
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        walk(child, key, found);
      }
    }
  }
}
