package com.facebed.service;

import com.facebed.config.FacebedConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class NotifierService {
  private static final Logger log = LoggerFactory.getLogger(NotifierService.class);

  private final FacebedConfig config;
  private final HttpClient client;
  private final ObjectMapper mapper;

  public NotifierService(FacebedConfig config, HttpClient client, ObjectMapper mapper) {
    this.config = config;
    this.client = client;
    this.mapper = mapper;
  }

  public void warn(String message) {
    send(message, null, null);
  }

  public void warn(String message, String title, String description) {
    send(message, title, description);
  }

  private void send(String message, String title, String description) {
    String webhook = config.notifierWebhook();
    if (webhook == null || webhook.isBlank() || !webhook.startsWith("https://discord.com/api/webhooks/")) {
      return;
    }

    CompletableFuture.runAsync(() -> {
      try {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (message != null && !message.isBlank()) {
          payload.put("content", message);
        }
        if ((title != null && !title.isBlank()) || (description != null && !description.isBlank())) {
          Map<String, Object> embed = new LinkedHashMap<>();
          embed.put("title", title == null ? "facebed" : title);
          embed.put("description", description == null ? "" : description);
          embed.put("color", 0x3498DB);
          payload.put("embeds", List.of(embed));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
      } catch (Exception e) {
        log.warn("failed to send notifier payload", e);
      }
    });
  }
}
