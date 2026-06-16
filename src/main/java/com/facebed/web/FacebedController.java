package com.facebed.web;

import com.facebed.service.AssetService;
import com.facebed.service.FacebookService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FacebedController {
  private static final Logger log = LoggerFactory.getLogger(FacebedController.class);

  private final FacebookService facebookService;
  private final AssetService assetService;

  public FacebedController(FacebookService facebookService, AssetService assetService) {
    this.facebookService = facebookService;
    this.assetService = assetService;
  }

  @GetMapping("/")
  public ResponseEntity<String> root(HttpServletRequest request) {
    ResponseEntity<String> response = facebookService.root();
    log(request, response);
    return response;
  }

  @GetMapping("/favicon.ico")
  public ResponseEntity<byte[]> favicon() {
    return assetService.favicon();
  }

  @GetMapping("/banner.png")
  public ResponseEntity<byte[]> banner() {
    return assetService.banner();
  }

  @RequestMapping("/**")
  public ResponseEntity<String> catchAll(HttpServletRequest request) {
    ResponseEntity<String> response = facebookService.handle(request);
    log(request, response);
    return response;
  }

  private void log(HttpServletRequest request, ResponseEntity<String> response) {
    String body = response.getBody();
    String title = "unknown";
    if (body != null) {
      String errorMatch = body.replaceAll("(?s).*content=\"Log in or sign up to view \\[(.*?)\\]\".*", "$1");
      if (!errorMatch.equals(body)) {
        title = "Error: " + errorMatch;
      } else {
        String meta = body.replaceAll("(?s).*content=\"([^\"]*)\".*", "$1");
        if (!meta.equals(body)) {
          title = meta;
        }
      }
    }
    String fullUrl = request.getRequestURL().toString();
    if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
      fullUrl = fullUrl + "?" + request.getQueryString();
    }
    log.info("{} {} {} {} {}", request.getRemoteAddr(), request.getMethod(), fullUrl, response.getStatusCodeValue(), title);
  }
}
