package com.facebed;

import com.facebed.config.FacebedConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class FacebedApplication {
  private static volatile FacebedConfig bootConfig;

  public static void main(String[] args) {
    String configPath = parseConfigPath(args);
    bootConfig = FacebedConfig.load(configPath);

    Map<String, Object> defaults = new HashMap<>();
    defaults.put("server.address", bootConfig.host());
    defaults.put("server.port", String.valueOf(bootConfig.port()));
    SpringApplication app = new SpringApplication(FacebedApplication.class);
    app.setDefaultProperties(defaults);
    app.run(args);
  }

  private static String parseConfigPath(String[] args) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if ("-c".equals(arg) || "--config".equals(arg)) {
        if (i + 1 < args.length) {
          return args[i + 1];
        }
      }
      if (arg.startsWith("--config=")) {
        return arg.substring("--config=".length());
      }
    }
    return null;
  }

  @Bean
  public FacebedConfig facebedConfig() {
    return bootConfig;
  }

  @Bean
  public HttpClient httpClient() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
