package com.facebed.service;

import com.facebed.config.FacebedConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class AssetService {
  private final FacebedConfig config;

  public AssetService(FacebedConfig config) {
    this.config = config;
  }

  public String rootHtml() {
    Path file = Paths.get("assets", "index.html");
    try {
      return Files.readString(file).replace("{|CREDIT|}", credit());
    } catch (IOException e) {
      throw new IllegalStateException("failed to read root asset", e);
    }
  }

  public ResponseEntity<byte[]> favicon() {
    return binary("assets/favicon.ico", "image/x-icon");
  }

  public ResponseEntity<byte[]> banner() {
    return binary("assets/banner.png", MediaType.IMAGE_PNG_VALUE);
  }

  private ResponseEntity<byte[]> binary(String path, String contentType) {
    try {
      byte[] data = Files.readAllBytes(Paths.get(path));
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, contentType)
          .body(data);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read asset " + path, e);
    }
  }

  private String credit() {
    return "facebed by pi.kt";
  }
}
