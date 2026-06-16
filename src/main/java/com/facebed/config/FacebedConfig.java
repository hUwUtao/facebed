package com.facebed.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public record FacebedConfig(
    String host,
    int port,
    int timezone,
    List<String> bannedUsers,
    String notifierWebhook,
    String cookieHeader
) {
  private static final Logger log = LoggerFactory.getLogger(FacebedConfig.class);

  public static FacebedConfig load(String configPath) {
    Map<String, Object> defaults = new HashMap<>();
    defaults.put("host", "0.0.0.0");
    defaults.put("port", 9812);
    defaults.put("timezone", 7);
    defaults.put("banned_users", List.of());
    defaults.put("notifier_webhook", "");

    Map<String, Object> config = new HashMap<>(defaults);
    if (configPath != null && !configPath.isBlank()) {
      Path path = Paths.get(configPath);
      if (!Files.isRegularFile(path)) {
        throw new IllegalStateException("config file " + configPath + " not found or is not a file");
      }
      if (!Files.isReadable(path)) {
        throw new IllegalStateException("config file " + configPath + " not readable");
      }

      Object loaded = new Yaml().load(readString(path));
      if (loaded instanceof Map<?, ?> loadedMap) {
        for (Map.Entry<?, ?> entry : loadedMap.entrySet()) {
          String key = String.valueOf(entry.getKey());
          if (!defaults.containsKey(key)) {
            throw new IllegalStateException("invalid config entry " + key);
          }
          config.put(key, entry.getValue());
        }
      } else if (loaded != null) {
        throw new IllegalStateException("config file must contain a mapping");
      }
    }

    String host = requireString(config, "host");
    int port = requireInt(config, "port");
    int timezone = requireInt(config, "timezone");
    if (timezone < -12 || timezone > 14) {
      throw new IllegalStateException("invalid timezone offset");
    }

    List<String> bannedUsers = new ArrayList<>();
    Object rawBanned = config.get("banned_users");
    if (rawBanned instanceof List<?> list) {
      for (Object item : list) {
        bannedUsers.add(String.valueOf(item));
      }
    } else {
      throw new IllegalStateException("invalid config entry banned_users");
    }

    String notifierWebhook = requireString(config, "notifier_webhook");
    String cookieHeader = loadCookiesHeader();
    return new FacebedConfig(host, port, timezone, Collections.unmodifiableList(bannedUsers), notifierWebhook, cookieHeader);
  }

  private static String loadCookiesHeader() {
    Path path = Paths.get("cookies.json");
    if (!Files.isRegularFile(path)) {
      log.warn("cookies.json not found, non incognito-viewable posts will NOT work");
      return "";
    }

    try {
      List<Map<String, Object>> cookies = new ObjectMapper().readValue(readBytes(path), new TypeReference<>() {});
      log.info("loaded {} cookies from {}", cookies.size(), path);
      long now = Instant.now().getEpochSecond();
      for (Map<String, Object> cookie : cookies) {
        Object expiration = cookie.getOrDefault("expirationDate", Long.MAX_VALUE);
        long expirationValue = expiration instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(expiration));
        if (expirationValue <= now) {
          log.warn("@everyone cookies expired");
          return "";
        }
      }

      StringJoiner joiner = new StringJoiner("; ");
      for (Map<String, Object> cookie : cookies) {
        String name = String.valueOf(cookie.getOrDefault("name", ""));
        String value = String.valueOf(cookie.getOrDefault("value", ""));
        if (!name.isBlank()) {
          joiner.add(name + "=" + value);
        }
      }
      return joiner.toString();
    } catch (Exception e) {
      log.warn("failed to read cookies.json", e);
      return "";
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read " + path, e);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read " + path, e);
    }
  }

  private static String requireString(Map<String, Object> config, String key) {
    Object value = config.get(key);
    if (value == null) {
      return "";
    }
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalStateException("invalid config entry " + key);
  }

  private static int requireInt(Map<String, Object> config, String key) {
    Object value = config.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String s && !s.isBlank()) {
      return Integer.parseInt(s);
    }
    throw new IllegalStateException("invalid config entry " + key);
  }
}
