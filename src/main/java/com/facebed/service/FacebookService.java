package com.facebed.service;

import com.facebed.config.FacebedConfig;
import com.facebed.model.ParsedPost;
import com.facebed.model.RenderMode;
import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.NativeWebRequest;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FacebookService {
  private static final Logger log = LoggerFactory.getLogger(FacebookService.class);
  private static final String WWWFB = "https://www.facebook.com";
  private static final Pattern CRAWLER = Pattern.compile("(?i)(bot|crawler|spider|preview|embed|slack|discord|telegram|whatsapp|facebookexternalhit|twitterbot|linkedinbot|pinterest|googlebot|bingbot|duckduckbot|yandex)");

  private final FacebedConfig config;
  private final HttpClient client;
  private final JsonSearchService jsonSearch;
  private final NotifierService notifier;
  private final AssetService assets;

  public FacebookService(FacebedConfig config, HttpClient client, JsonSearchService jsonSearch, NotifierService notifier, AssetService assets) {
    this.config = config;
    this.client = client;
    this.jsonSearch = jsonSearch;
    this.notifier = notifier;
    this.assets = assets;
  }

  public ResponseEntity<String> root() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(assets.rootHtml());
  }

  public ResponseEntity<String> handle(HttpServletRequest request) {
    String rawPath = request.getRequestURI();
    String pathOnly = stripLeadingSlash(rawPath);
    String query = request.getQueryString();
    String relative = query == null || query.isBlank() ? pathOnly : pathOnly + "?" + query;
    String originalUrl = ensureFullUrl(relative);
    String userAgent = Objects.toString(request.getHeader("User-Agent"), "");

    try {
      if (matchesDump(relative)) {
        String dumpPath = stripDumpSuffix(relative);
        try {
          String rawHtml = fetchRawHtml(dumpPath, true);
          String filename = sanitize(dumpPath) + "_dump.html";
          String description = "🔗 [`/" + dumpPath + "`](" + ensureFullUrl(dumpPath) + ")\n📋 Manual dump requested by user\n" + "`" + filename + "`";
          notifier.warn(rawHtml.length() > 0 ? "manual dump generated" : "manual dump generated", "manual dump report", description);
          log.info("Dump report sent for /{}", dumpPath);
        } catch (Exception e) {
          log.error("Failed to generate dump for /{}", dumpPath, e);
        }
        return routeAndRender(dumpPath, ensureFullUrl(dumpPath), request, RenderMode.FULL);
      }

      if (!isCrawler(userAgent)) {
        return redirectToFacebook(originalUrl);
      }

      RenderMode mode = relative.endsWith("/text") ? RenderMode.TEXT : RenderMode.FULL;
      String pathForRouting = relative;
      if (pathForRouting.endsWith("/text")) {
        pathForRouting = pathForRouting.substring(0, pathForRouting.length() - "/text".length());
      }

      if (request.getParameter("type") != null && request.getParameter("type").contains("3")) {
        try {
          return renderPost(parsePhotocom(pathForRouting), mode);
        } catch (Exception ignored) {
          // fall through to normal routing
        }
      }

      return routeAndRender(pathForRouting, originalUrl, request, mode);
    } catch (NoDataException e) {
      log.info("No data available for /{} (login wall / restricted content)", pathOnly);
      return renderErrorMessageEmbed(originalUrl);
    } catch (ParseException e) {
      log.error("Parser bug for /{}", pathOnly, e);
      notifier.warn("embed failure: " + e.getMessage(), "embed failure", e.getMessage());
      return renderErrorMessageEmbed(originalUrl);
    } catch (Exception e) {
      log.error("Unexpected error for /{}", pathOnly, e);
      notifier.warn("unexpected facebed error", "unexpected error", e.toString());
      return renderErrorMessageEmbed(originalUrl);
    }
  }

  public ResponseEntity<byte[]> favicon() {
    return assets.favicon();
  }

  public ResponseEntity<byte[]> banner() {
    return assets.banner();
  }

  private ResponseEntity<String> routeAndRender(String pathForRouting, String originalUrl, HttpServletRequest request, RenderMode mode) {
    if (looksLikeShare(pathForRouting)) {
      String resolved = resolveShareLink(pathForRouting);
      if (resolved.isBlank()) {
        return renderErrorMessageEmbed(originalUrl);
      }
      pathForRouting = resolved;
      originalUrl = ensureFullUrl(pathForRouting);
    }

    if (pathForRouting.matches("^/?reel/[0-9]+.*")) {
      return renderPost(parseReel(pathForRouting), mode);
    }

    if (pathForRouting.matches("^/*photo(\\.php)*/*.*")) {
      return renderPost(parseSinglePhoto(pathForRouting), mode);
    }

    if (pathForRouting.matches("^/*watch.*")) {
      return renderPost(parseWatch(pathForRouting), mode);
    }

    if (isFacebookUrl(pathForRouting)) {
      return renderPost(parseGeneral(pathForRouting), mode);
    }

    return renderErrorMessageEmbed("https://git.facebed.com");
  }

  private boolean matchesDump(String relative) {
    return relative != null && relative.matches("(?i)^(.*)/dump/?$");
  }

  private String stripDumpSuffix(String relative) {
    String stripped = relative.replaceFirst("(?i)/dump/?$", "");
    return stripped.isBlank() ? "" : stripped;
  }

  private ResponseEntity<String> redirectToFacebook(String url) {
    return ResponseEntity.status(301)
        .header(HttpHeaders.LOCATION, url)
        .contentType(MediaType.TEXT_HTML)
        .body(renderRedirectPage(url));
  }

  private boolean isCrawler(String userAgent) {
    return userAgent != null && CRAWLER.matcher(userAgent).find();
  }

  private boolean looksLikeShare(String path) {
    return path.matches("^(/)?share/v/.*") || path.matches("^(/)?share/([pr]/)?[a-zA-Z0-9-._]*(/)?");
  }

  private String resolveShareLink(String path) {
    String url = ensureFullUrl(path);
    log.info("Resolving share link: {}", url);
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .GET()
          .header("User-Agent", "Mozilla/5.0 Facebed")
          .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      String resolved = response.uri().toString();
      log.info("Resolved to: {}", resolved);
      if (resolved.startsWith(WWWFB + "/share") || resolved.startsWith("https://web.facebook.com/share")) {
        log.warn("Share link still redirects to share page");
        return "";
      }
      for (String base : List.of("https://www.facebook.com", "https://web.facebook.com")) {
        if (resolved.startsWith(base + "/")) {
          return resolved.substring(base.length() + 1);
        }
      }
      return resolved;
    } catch (Exception e) {
      log.warn("failed to resolve share link {}", path, e);
      return "";
    }
  }

  private ResponseEntity<String> renderPost(ParsedPost post, RenderMode mode) {
    if (post == null) {
      return renderErrorMessageEmbed(WWWFB);
    }
    String body = mode == RenderMode.TEXT ? renderTextPost(post) : renderFullPost(post);
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
  }

  private ResponseEntity<String> renderErrorMessageEmbed(String originalUrl) {
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(renderErrorPage(originalUrl));
  }

  private ParsedPost parseGeneral(String postPath) {
    FetchedPage page = fetchPage(postPath, true);
    JsonNode postJson = selectPostJson(page.document);
    JsonNode root = selectRootNode(postJson);
    Counts counts = getCounts(root, postJson);
    long date = findDate(root, page.document);

    JsonNode storyNode = selectStoryNode(root);
    Story story = parseStory(storyNode, root, postPath);
    String groupName = getGroupName(page.document);
    String header = story.authorName + (groupName.isBlank() ? "" : " • " + groupName);

    if (config.bannedUsers().contains(story.authorId)) {
      return banned(story.url.isBlank() ? ensureFullUrl(postPath) : story.url);
    }

    return new ParsedPost(header, story.text.strip(), story.imageLinks, story.url.isBlank() ? ensureFullUrl(postPath) : story.url, date, counts.likes, counts.comments, counts.shares, story.videoLinks);
  }

  private ParsedPost parseSinglePhoto(String postPath) {
    FetchedPage page = fetchPage(postPath, true);
    JsonNode contentNode = null;
    JsonNode interactions = null;
    for (JsonNode block : jsonSearch.jsonBlocks(page.document)) {
      if (contentNode == null && jsonSearch.has(block, "message_preferred_body", "container_story")) {
        contentNode = jsonSearch.path(block, "data");
      }
      if (interactions == null && jsonSearch.has(block, "comet_ufi_summary_and_actions_renderer")) {
        interactions = block;
      }
    }
    if (contentNode == null || interactions == null) {
      return parseGeneral(postPath);
    }

    String text = "";
    JsonNode message = jsonSearch.path(contentNode, "message");
    if (message != null) {
      JsonNode txt = message.get("text");
      text = txt == null ? "" : txt.asText("");
    }
    String author = textOr(contentNode, "owner", "name");
    long date = contentNode.path("created_time").asLong(-1L);
    Counts counts = extractCounts(interactions);
    String image = findSingleImage(page.document);
    return new ParsedPost(author, text.strip(), List.of(image), ensureFullUrl(postPath), date, counts.likes, counts.comments, counts.shares, List.of());
  }

  private ParsedPost parsePhotocom(String postPath) {
    FetchedPage page = fetchPage(postPath, true);
    JsonNode contentNode = null;
    JsonNode reactionNode = null;
    for (JsonNode block : jsonSearch.jsonBlocks(page.document)) {
      if (contentNode == null && jsonSearch.has(block, "attached_comment") && !jsonSearch.has(block, "unified_reactors")) {
        contentNode = jsonSearch.path(block, "result");
      }
      if (reactionNode == null && jsonSearch.has(block, "attached_comment", "unified_reactors")) {
        reactionNode = block;
      }
    }
    if (contentNode == null || reactionNode == null) {
      return parseGeneral(postPath);
    }

    JsonNode body = jsonSearch.path(contentNode, "data", "attached_comment", "preferred_body");
    String postText = body == null || body.isNull() ? "" : body.path("text").asText("");
    String opName = textOr(contentNode, "data", "owner", "name") + " (💬)";
    long date = contentNode.path("data").path("created_time").asLong(-1L);
    String image = textOr(reactionNode, "currMedia", "image", "uri");
    String url = textOr(reactionNode, "currMedia", "attached_comment", "feedback", "url");
    String likes = humanFormat(textOr(reactionNode, "unified_reactors", "count"));
    return new ParsedPost(opName, postText.strip(), List.of(image), url, date, likes, "null", "null", List.of());
  }

  private ParsedPost parseReel(String postPath) {
    FetchedPage page = fetchPage(postPath, true);
    JsonNode contentNode = null;
    for (JsonNode block : jsonSearch.jsonBlocks(page.document)) {
      if (contentNode == null && (jsonSearch.has(block, "browser_native_sd_url", "creation_story") || jsonSearch.has(block, "short_form_video_context"))) {
        contentNode = block;
      }
    }
    if (contentNode == null) {
      throw new ParseException("Invalid reels link");
    }

    String videoLink = findVideoLink(page.document, null);
    String videoId = textOr(contentNode, "id");
    if (videoId.isBlank()) {
      videoId = textOr(contentNode, "video", "id");
    }
    JsonNode ownerInfo = jsonSearch.path(contentNode, "short_form_video_context", "video_owner");
    if (ownerInfo == null || ownerInfo.isNull()) {
      ownerInfo = jsonSearch.path(contentNode, "video_owner");
    }
    if (ownerInfo == null || ownerInfo.isNull()) {
      ownerInfo = jsonSearch.first(contentNode, "video_owner");
    }
    String typename = textOr(ownerInfo, "__typename");
    boolean isIg = typename.startsWith("InstagramUser");
    String opName = (isIg ? "📷 @" : "") + textOr(ownerInfo, isIg ? "username" : "name");
    String postUrl = textOr(contentNode, "short_form_video_context", "shareable_url");
    if (postUrl.isBlank()) {
      postUrl = ensureFullUrl(postPath);
    }
    long date = textOr(contentNode, "creation_time").isBlank() ? -1L : Long.parseLong(textOr(contentNode, "creation_time"));
    if (date == -1L) {
      date = firstTimestamp(page.document);
    }
    String text = textOr(contentNode, "message", "text");
    Counts counts = getReelCounts(page.document, isIg, videoId);

    String ownerId = textOr(ownerInfo, "id");
    if (config.bannedUsers().contains(ownerId)) {
      return banned(postUrl);
    }

    return new ParsedPost(opName, text.strip(), List.of(), postUrl, date, counts.likes, counts.comments, counts.shares, List.of(videoLink));
  }

  private ParsedPost parseWatch(String postPath) {
    FetchedPage page = fetchPage(postPath, true);
    JsonNode contentNode = null;
    for (JsonNode block : jsonSearch.jsonBlocks(page.document)) {
      if (jsonSearch.has(block, "comment_rendering_instance", "video_view_count_renderer")) {
        contentNode = jsonSearch.path(block, "result", "data");
        break;
      }
    }
    if (contentNode == null) {
      throw new NoDataException("Facebook served generic watch feed instead of specific video");
    }

    String videoLink = findVideoLink(page.document, null);
    String opName = findWatchOpName(page.document);
    String postText = textOr(contentNode, "title", "text");
    if (postText.isBlank()) {
      postText = textOr(contentNode, "message", "text");
    }
    String likes = humanFormat(textOr(contentNode, "feedback", "reaction_count", "count"));
    String comments = humanFormat(textOr(contentNode, "feedback", "total_comment_count"));
    long date = firstTimestamp(page.document);
    return new ParsedPost(opName, postText.strip(), List.of(), ensureFullUrl(postPath), date, likes, comments, "null", List.of(videoLink));
  }

  private String findWatchOpName(Document document) {
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      if (jsonSearch.has(block, "is_additional_profile_plus")) {
        return textOr(jsonSearch.path(block, "owner"), "name");
      }
    }
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      JsonNode owner = jsonSearch.path(block, "owner");
      String name = textOr(owner, "name");
      if (!name.isBlank()) {
        return name;
      }
    }
    throw new ParseException("Invalid watch link (opn)");
  }

  private ParsedPost banned(String url) {
    notifier.warn("banned embed attempted \"" + url + "\"");
    return new ParsedPost("Banned", "This user is banned by the operators of this embed server", List.of(), "https://banned.facebook.com", -1L, "null", "null", "null", List.of());
  }

  private FetchedPage fetchPage(String relativePath, boolean useCookies) {
    String url = ensureFullUrl(relativePath);
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
          .GET()
          .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
          .header("accept-language", "en-US,en;q=0.9")
          .header("cache-control", "no-cache")
          .header("pragma", "no-cache")
          .header("priority", "u=0, i")
          .header("sec-fetch-mode", "navigate")
          .header("sec-fetch-site", "none")
          .header("User-Agent", "Mozilla/5.0 Facebed");
      if (useCookies && !config.cookieHeader().isBlank()) {
        builder.header("Cookie", config.cookieHeader());
      }
      HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      String rawHtml = response.body();
      Document document = Jsoup.parse(rawHtml, url);
      checkPageOrThrow(document, relativePath);
      return new FetchedPage(document, rawHtml, response.uri().toString());
    } catch (NoDataException e) {
      throw e;
    } catch (Exception e) {
      throw new ParseException("failed to fetch " + relativePath, "", url, e);
    }
  }

  private String fetchRawHtml(String relativePath, boolean useCookies) {
    String url = ensureFullUrl(relativePath);
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
          .GET()
          .header("User-Agent", "Mozilla/5.0 Facebed");
      if (useCookies && !config.cookieHeader().isBlank()) {
        builder.header("Cookie", config.cookieHeader());
      }
      HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return response.body();
    } catch (Exception e) {
      throw new ParseException("failed to fetch raw html", "", url, e);
    }
  }

  private void checkPageOrThrow(Document document, String postPath) {
    String pageType = probePageType(document);
    if ("login_wall".equals(pageType) || "no_data".equals(pageType)) {
      throw new NoDataException("Facebook served a login wall or empty page for " + postPath + " - content requires authentication");
    }
  }

  private String probePageType(Document document) {
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      if (jsonSearch.has(block, "i18n_reaction_count") || jsonSearch.has(block, "short_form_video_context")) {
        return "has_data";
      }
    }
    Element canonical = document.selectFirst("link[rel=canonical]");
    if (canonical != null && canonical.attr("href").matches(".*?/login\\b.*")) {
      return "login_wall";
    }
    return "no_data";
  }

  private JsonNode selectPostJson(Document document) {
    List<JsonNode> candidates = new ArrayList<>();
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      if (jsonSearch.has(block, "i18n_reaction_count") || jsonSearch.has(block, "short_form_video_context")) {
        candidates.add(block);
      }
    }
    if (candidates.isEmpty()) {
      for (JsonNode block : jsonSearch.jsonBlocks(document)) {
        String blob = block.toString();
        if (blob.contains("video") && blob.contains("short_form_video_context")) {
          candidates.add(block);
        }
      }
    }
    if (candidates.isEmpty()) {
      throw new ParseException("cannot find post json");
    }
    candidates.sort(Comparator.comparingInt(this::scoreBlock).reversed());
    return candidates.get(0);
  }

  private int scoreBlock(JsonNode block) {
    int score = 0;
    if (jsonSearch.has(block, "short_form_video_context")) {
      score += 20;
    }
    if (jsonSearch.has(block, "creation_story")) {
      score += 10;
    }
    if (jsonSearch.has(block, "comet_sections")) {
      score += 5;
    }
    if (jsonSearch.has(block, "group_hoisted_feed")) {
      score += 8;
    }
    if (jsonSearch.has(block, "video_home_www_related_videos_section") || jsonSearch.has(block, "video_home_www_loe_video_permalink_seo_info")) {
      score -= 20;
    }
    JsonNode nodeV2 = jsonSearch.first(block, "node_v2");
    if (nodeV2 != null && nodeV2.isObject()) {
      if (nodeV2.has("actors")) {
        score += 30;
      }
      if (nodeV2.has("feedback") && nodeV2.get("feedback").isObject()) {
        score += 15;
      }
      if (nodeV2.has("comet_sections") || nodeV2.has("creation_story")) {
        score += 10;
      }
    }
    JsonNode data = jsonSearch.first(block, "data");
    if (data != null && data.isObject()) {
      if (data.has("actors")) {
        score += 20;
      }
      if (data.has("feedback") && data.get("feedback").isObject()) {
        score += 10;
      }
    }
    if (block.has("require") && nodeV2 == null && data == null) {
      score -= 10;
    }
    return score;
  }

  private JsonNode selectRootNode(JsonNode postJson) {
    if (postJson == null || postJson.isNull()) {
      throw new ParseException("Cannot process post");
    }

    JsonNode data = postJson.path("data");
    if (data.isObject()) {
      if (data.has("comet_ufi_summary_and_actions_renderer")) {
        return data;
      }
      JsonNode nodeV2 = data.get("node_v2");
      if (nodeV2 != null && nodeV2.isObject() && (nodeV2.has("comet_sections") || nodeV2.has("creation_story"))) {
        return nodeV2;
      }
      JsonNode node = data.get("node");
      if (node != null && node.isObject() && (node.has("comet_sections") || node.has("creation_story"))) {
        return node;
      }
    }

    JsonNode shortForm = jsonSearch.first(postJson, "short_form_video_context");
    if (shortForm != null && shortForm.isObject()) {
      return jsonSearch.path(postJson, "short_form_video_context");
    }

    JsonNode hoisted = jsonSearch.first(postJson, "group_hoisted_feed");
    if (hoisted != null && hoisted.isObject()) {
      return hoisted;
    }

    if (postJson.has("creation_story") && postJson.has("feedback")) {
      return postJson;
    }
    JsonNode nodeV2 = postJson.get("node_v2");
    if (nodeV2 != null && nodeV2.isObject()) {
      return nodeV2;
    }
    return postJson;
  }

  private JsonNode selectStoryNode(JsonNode root) {
    JsonNode content = jsonSearch.path(root, "content", "story");
    if (content != null && content.isObject()) {
      return content;
    }
    JsonNode creation = jsonSearch.first(root, "creation_story");
    if (creation != null && creation.isObject()) {
      return creation;
    }
    JsonNode sectionsStory = jsonSearch.path(root, "comet_sections", "content", "story");
    if (sectionsStory != null && sectionsStory.isObject()) {
      return sectionsStory;
    }
    JsonNode shortForm = jsonSearch.first(root, "short_form_video_context");
    if (shortForm != null && shortForm.isObject()) {
      return shortForm;
    }
    return root;
  }

  private Story parseStory(JsonNode storyNode, JsonNode root, String postPath) {
    if (storyNode == null || storyNode.isNull()) {
      storyNode = root;
    }

    String authorName = firstNonBlank(
        textOr(storyNode, "actors", "0", "name"),
        textOr(storyNode, "owner", "name"),
        textOr(storyNode, "name"),
        textOr(storyNode, "short_name"),
        textOr(root, "actors", "0", "name"),
        textOr(root, "owner", "name")
    );

    String authorId = firstNonBlank(
        textOr(storyNode, "actors", "0", "id"),
        textOr(storyNode, "owner", "id"),
        textOr(storyNode, "id"),
        textOr(root, "actors", "0", "id"),
        textOr(root, "owner", "id")
    );

    String text = firstNonBlank(
        textOr(storyNode, "message", "text"),
        textOr(storyNode, "text"),
        textOr(root, "message", "text"),
        textOr(root, "text")
    );

    String url = firstNonBlank(
        textOr(storyNode, "wwwURL"),
        textOr(storyNode, "url"),
        textOr(root, "wwwURL"),
        textOr(root, "url")
    );

    LinkedHashSet<String> images = new LinkedHashSet<>(collectImageLinks(root));
    LinkedHashSet<String> videos = new LinkedHashSet<>(collectVideoLinks(root, storyNode));

    JsonNode attached = jsonSearch.path(storyNode, "attached_story");
    if (attached != null && attached.isObject() && attached.has("actors")) {
      Story nested = parseStory(attached, root, postPath);
      if (!nested.text.isBlank()) {
        text = text + "\n╰┈➤ " + nested.authorName + "\n" + nested.text;
      }
      images.addAll(nested.imageLinks);
      videos.addAll(nested.videoLinks);
    }

    if (authorName.isBlank()) {
      authorName = firstNonBlank(textOr(root, "name"), textOr(root, "localized_name"), "");
    }
    if (authorId.isBlank()) {
      authorId = firstNonBlank(textOr(root, "id"), "");
    }

    return new Story(authorName, authorId, text, url, List.copyOf(images), List.copyOf(videos));
  }

  private List<String> collectImageLinks(JsonNode node) {
    LinkedHashSet<String> images = new LinkedHashSet<>();
    for (JsonNode attachment : jsonSearch.all(node, "attachment")) {
      if (attachment == null) {
        continue;
      }
      if (attachment.toString().contains("Sticker")) {
        continue;
      }
      for (JsonNode viewer : jsonSearch.all(attachment, "viewer_image")) {
        String uri = textOr(viewer, "uri");
        if (!uri.isBlank()) {
          images.add(uri);
        }
      }
      for (JsonNode photo : jsonSearch.all(attachment, "photo_image")) {
        String uri = textOr(photo, "uri");
        if (!uri.isBlank()) {
          images.add(uri);
        }
      }
    }
    for (JsonNode renderer : jsonSearch.all(node, "comet_photo_attachment_resolution_renderer")) {
      String uri = textOr(renderer, "image", "uri");
      if (!uri.isBlank()) {
        images.add(uri);
      }
    }
    for (JsonNode uris : jsonSearch.all(node, "prefetch_uris_v2")) {
      if (uris.isArray() && !uris.isEmpty()) {
        String uri = textOr(uris.get(0), "uri");
        if (!uri.isBlank()) {
          images.add(uri);
        }
      }
    }
    if (images.isEmpty()) {
      String og = firstMeta(node, "og:image");
      if (!og.isBlank()) {
        images.add(og);
      }
    }
    return new ArrayList<>(images);
  }

  private List<String> collectVideoLinks(JsonNode root, JsonNode storyNode) {
    LinkedHashSet<String> videos = new LinkedHashSet<>();
    List<JsonNode> sources = List.of(storyNode, root);
    for (JsonNode source : sources) {
      if (source == null) {
        continue;
      }
      for (JsonNode legacy : jsonSearch.all(source, "videoDeliveryLegacyFields")) {
        for (String key : List.of("browser_native_hd_url", "browser_native_sd_url")) {
          String link = textOr(legacy, key);
          if (!link.isBlank()) {
            videos.add(link);
          }
        }
      }
      for (String key : List.of("browser_native_hd_url", "browser_native_sd_url", "playable_url", "video_url")) {
        for (JsonNode node : jsonSearch.all(source, key)) {
          String link = node == null ? "" : node.asText("");
          if (!link.isBlank()) {
            videos.add(link);
          }
        }
      }
    }
    return new ArrayList<>(videos);
  }

  private String findVideoLink(Document document, JsonNode userNode) {
    if (userNode != null) {
      for (String key : List.of("browser_native_hd_url", "browser_native_sd_url")) {
        String link = textOr(userNode, "videoDeliveryLegacyFields", key);
        if (!link.isBlank()) {
          return link;
        }
      }
    }
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      if (jsonSearch.has(block, "browser_native_hd_url") || jsonSearch.has(block, "browser_native_sd_url")) {
        String link = textOr(block, "browser_native_hd_url");
        if (link.isBlank()) {
          link = textOr(block, "browser_native_sd_url");
        }
        if (!link.isBlank()) {
          return link;
        }
        JsonNode legacy = jsonSearch.first(block, "videoDeliveryLegacyFields");
        if (legacy != null) {
          link = textOr(legacy, "browser_native_hd_url");
          if (link.isBlank()) {
            link = textOr(legacy, "browser_native_sd_url");
          }
          if (!link.isBlank()) {
            return link;
          }
        }
      }
    }
    throw new ParseException("Invalid reels link (vn)");
  }

  private Counts extractCounts(JsonNode node) {
    String reactions = firstNonBlank(textOr(node, "i18n_reaction_count"), textOr(jsonSearch.first(node, "i18n_reaction_count")));
    String shares = firstNonBlank(textOr(node, "i18n_share_count"), textOr(node, "share_count"), textOr(jsonSearch.first(node, "i18n_share_count")), textOr(jsonSearch.first(node, "share_count")));
    String comments = firstNonBlank(textOr(node, "total_comment_count"), textOr(jsonSearch.first(node, "total_comment_count")));
    if (comments.isBlank()) {
      comments = firstNonBlank(textOr(node, "comment_rendering_instance", "comments", "total_count"), textOr(node, "comments_count_summary_renderer", "feedback", "comment_rendering_instance", "comments", "total_count"));
    }
    return new Counts(blankToZero(reactions), blankToZero(comments), blankToZero(shares));
  }

  private Counts getCounts(JsonNode root, JsonNode postJson) {
    JsonNode postFeedback = jsonSearch.first(postJson, "comet_ufi_summary_and_actions_renderer");
    if (postFeedback != null && postFeedback.isObject() && postFeedback.has("feedback")) {
      return extractCounts(postFeedback.get("feedback"));
    }

    JsonNode ufi = jsonSearch.first(postJson, "comet_ufi_summary_and_actions_renderer");
    if (ufi != null && ufi.isObject()) {
      JsonNode feedback = ufi.get("feedback");
      if (feedback != null && feedback.isObject()) {
        Counts counts = extractCounts(feedback);
        if (!counts.isEmpty()) {
          return counts;
        }
      }
    }

    JsonNode fb = jsonSearch.first(postJson, "feedback");
    if (fb != null && fb.isObject()) {
      Counts counts = extractCounts(fb);
      if (!counts.isEmpty()) {
        return counts;
      }
    }

    JsonNode best = bestFeedback(postJson);
    if (best != null) {
      Counts counts = extractCounts(best);
      if (!counts.isEmpty()) {
        return counts;
      }
    }

    return new Counts(blankToZero(firstNonBlank(textOr(postJson, "i18n_reaction_count"), "0")), blankToZero(firstNonBlank(textOr(postJson, "total_comment_count"), "0")), blankToZero(firstNonBlank(textOr(postJson, "i18n_share_count"), textOr(postJson, "share_count"), "0")));
  }

  private JsonNode bestFeedback(JsonNode postJson) {
    JsonNode best = null;
    int bestReactions = 0;
    for (JsonNode fb : jsonSearch.all(postJson, "feedback")) {
      if (!fb.isObject()) {
        continue;
      }
      String rc = textOr(fb, "i18n_reaction_count");
      if (!rc.isBlank()) {
        try {
          int value = Integer.parseInt(rc.replaceAll("[^0-9]", ""));
          if (value > bestReactions) {
            bestReactions = value;
            best = fb;
          }
        } catch (Exception ignored) {
        }
      } else if (best == null) {
        best = fb;
      }
    }
    return best;
  }

  private Counts getReelCounts(Document document, boolean isIg, String videoId) {
    List<JsonNode> blocks = new ArrayList<>();
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      if (jsonSearch.has(block, "unified_reactors")) {
        for (JsonNode id : jsonSearch.all(block, "id")) {
          if (videoId != null && videoId.equals(id.asText(""))) {
            blocks.add(block);
            break;
          }
        }
      }
    }
    if (blocks.isEmpty()) {
      throw new ParseException("Cannot process post (cn)");
    }

    JsonNode block = blocks.get(0);
    JsonNode firstFb = jsonSearch.first(block, "feedback");
    JsonNode lastFb = jsonSearch.all(block, "feedback").isEmpty() ? firstFb : jsonSearch.all(block, "feedback").get(jsonSearch.all(block, "feedback").size() - 1);
    if (firstFb != null && firstFb.toString().contains("cross_universe_feedback_info")) {
      JsonNode swap = firstFb;
      firstFb = lastFb;
      lastFb = swap;
    }

    String likes = humanFormat(textOr(firstFb, "unified_reactors", "count"));
    String comments = isIg ? humanFormat(textOr(lastFb, "cross_universe_feedback_info", "ig_comment_count")) : humanFormat(textOr(lastFb, "total_comment_count"));
    String shares = humanFormat(textOr(lastFb, "share_count_reduced"));
    return new Counts(blankToZero(likes), blankToZero(comments), blankToZero(shares));
  }

  private long findDate(JsonNode root, Document document) {
    String direct = firstNonBlank(textOr(root, "creation_time"), textOr(root, "created_time"));
    if (!direct.isBlank()) {
      try {
        return Long.parseLong(direct);
      } catch (Exception ignored) {
      }
    }
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      String date = firstNonBlank(textOr(block, "creation_time"), textOr(block, "created_time"));
      if (!date.isBlank()) {
        try {
          return Long.parseLong(date);
        } catch (Exception ignored) {
        }
      }
    }
    return -1L;
  }

  private long firstTimestamp(Document document) {
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      String date = firstNonBlank(textOr(block, "creation_time"), textOr(block, "created_time"));
      if (!date.isBlank()) {
        try {
          return Long.parseLong(date);
        } catch (Exception ignored) {
        }
      }
    }
    return -1L;
  }

  private String getGroupName(Document document) {
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      if (jsonSearch.has(block, "group_member_profiles", "formatted_count_text")) {
        for (JsonNode group : jsonSearch.all(block, "group")) {
          String name = textOr(group, "name");
          if (!name.isBlank()) {
            return name;
          }
        }
      }
    }
    return "";
  }

  private String renderFullPost(ParsedPost post) {
    if (!post.videoLinks().isEmpty()) {
      return renderReelPost(post);
    }

    List<String> images = new ArrayList<>(post.imageLinks());
    String imageCounter = images.size() > 4 ? "\ncontains 4+ images" : "";
    images = images.subList(0, Math.min(4, images.size()));
    StringJoiner imageTags = new StringJoiner("\n");
    for (String image : images) {
      imageTags.add("<meta property=\"og:image\" content=\"" + esc(image) + "\"/>");
    }

    String reactionStr = formatReactions(post.likes(), post.comments(), post.shares());
    String postDate = timestampToString(post.date());
    return prettify("""
        <!DOCTYPE html>
        <html lang="">
        <head>
            <title>%s</title>
            <meta charset="UTF-8"/>
            <meta property="og:title" content="%s"/>
            <meta property="og:description" content="%s"/>
            <meta property="og:site_name" content="%s\n%s\n%s%s"/>
            <meta property="og:url" content="%s"/>
            %s
            <link rel="canonical" href="%s"/>
            <meta http-equiv="refresh" content="0;url=%s"/>
            <meta name="twitter:card" content="summary_large_image"/>
            <meta name="theme-color" content="#0866ff"/>
        </head>
        </html>
        """.formatted(
        esc(credit()), esc(post.authorName()), esc(truncate(post.text(), 1024)), esc(credit()), esc(postDate), esc(reactionStr), esc(imageCounter), esc(post.url()), imageTags, esc(post.url()), esc(post.url())));
  }

  private String renderTextPost(ParsedPost post) {
    String reactionStr = formatReactions(post.likes(), post.comments(), post.shares());
    String postDate = timestampToString(post.date());
    return prettify("""
        <!DOCTYPE html>
        <html lang="">
        <head>
            <title>%s</title>
            <meta charset="UTF-8"/>
            <meta property="og:title" content="%s"/>
            <meta property="og:description" content="%s"/>
            <meta property="og:site_name" content="%s\n%s\n%s"/>
            <meta property="og:url" content="%s"/>
            <link rel="canonical" href="%s"/>
            <meta http-equiv="refresh" content="0;url=%s"/>
            <meta name="twitter:card" content="summary"/>
            <meta name="theme-color" content="#0866ff"/>
        </head>
        </html>
        """.formatted(esc(credit()), esc(post.authorName()), esc(truncate(post.text(), 1024)), esc(credit()), esc(postDate), esc(reactionStr), esc(post.url()), esc(post.url()), esc(post.url())));
  }

  private String renderReelPost(ParsedPost post) {
    StringJoiner videoTags = new StringJoiner("\n");
    for (String link : post.videoLinks()) {
      videoTags.add("<meta property=\"twitter:player:stream\" content=\"" + esc(link) + "\"/>\n" +
          "<meta property=\"og:video\" content=\"" + esc(link) + "\"/>\n" +
          "<meta property=\"og:video:secure_url\" content=\"" + esc(link) + "\"/>");
    }

    String reactionStr = formatReactions(post.likes(), post.comments(), post.shares());
    String postDate = timestampToString(post.date());
    return prettify("""
        <!DOCTYPE html>
        <html lang="">
        <head>
            <title>%s</title>
            <meta charset="UTF-8"/>
            <meta property="og:title" content="%s"/>
            <meta property="og:description" content="%s"/>
            <meta property="og:site_name" content="%s\n%s\n%s"/>
            <meta property="og:url" content="%s"/>
            <meta property="og:video:type" content="video/mp4"/>
            <meta property="twitter:player:stream:content_type" content="video/mp4"/>
            %s
            <link rel="canonical" href="%s"/>
            <meta http-equiv="refresh" content="0;url=%s"/>
            <meta name="twitter:card" content="player"/>
            <meta name="theme-color" content="#0866ff"/>
        </head>
        </html>
        """.formatted(esc(credit()), esc(post.authorName()), esc(truncate(post.text(), 1024)), esc(credit()), esc(postDate), esc(reactionStr), esc(post.url()), videoTags, esc(post.url()), esc(post.url())));
  }

  private String renderErrorPage(String originalUrl) {
    return prettify("""
        <!DOCTYPE html>
        <html lang="">
        <head>
        <meta charset="UTF-8" />
            <meta name="theme-color" content="#2c3048f" />
            <meta property="og:title" content="Log in or sign up to view"/>
            <meta property="og:description" content="See posts, photos and more on Facebook."/>
            <meta http-equiv="refresh" content="0;url=%s"/>
        </head>
        </html>
        """.formatted(esc(originalUrl)));
  }

  private String renderRedirectPage(String url) {
    return prettify("""
        <!DOCTYPE HTML>
        <html lang="en-US">
            <head>
                <meta charset="UTF-8">
                <meta http-equiv="refresh" content="0; url=%s">
                <script type="text/javascript">
                    window.location.href = "%s"
                </script>
                <title>redirecting...</title>
            </head>
            <body>
            </body>
        </html>
        """.formatted(esc(url), jsEsc(url)));
  }

  private String prettify(String html) {
    return html.replaceAll("(?m)^", "    ");
  }

  private String esc(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private String jsEsc(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private String credit() {
    return "facebed by pi.kt";
  }

  private String timestampToString(long ts) {
    if (ts < 0) {
      return "";
    }
    return "⌚ " + DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss 'UTC'XXX", Locale.ROOT)
        .withZone(ZoneOffset.ofHours(config.timezone()))
        .format(Instant.ofEpochSecond(ts));
  }

  private String humanFormat(String value) {
    if (value == null || value.isBlank() || "null".equals(value)) {
      return "null";
    }
    String digits = value.replaceAll("[^0-9]", "");
    if (digits.isBlank()) {
      return value;
    }
    long num = Long.parseLong(digits);
    String[] suffixes = {"", "K", "M", "B", "T"};
    double d = num;
    int mag = 0;
    while (Math.abs(d) >= 1000 && mag < suffixes.length - 1) {
      d /= 1000.0;
      mag++;
    }
    String text = String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    return text + suffixes[mag];
  }

  private String formatReactions(String likes, String comments, String shares) {
    List<String> parts = new ArrayList<>();
    if (!"null".equals(likes)) {
      parts.add("❤️ " + likes);
    }
    if (!"null".equals(comments)) {
      parts.add("💬 " + comments);
    }
    if (!"null".equals(shares)) {
      parts.add("🔁 " + shares);
    }
    return String.join(" • ", parts).replace(",", ".");
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private String ensureFullUrl(String relative) {
    if (relative == null || relative.isBlank()) {
      return WWWFB;
    }
    if (relative.startsWith(WWWFB)) {
      return relative;
    }
    return WWWFB + "/" + relative.replaceFirst("^/", "");
  }

  private boolean isFacebookUrl(String url) {
    String normalized = url == null ? "" : url.replaceFirst("^/", "");
    String path = normalized;
    int q = normalized.indexOf('?');
    if (q >= 0) {
      path = normalized.substring(0, q);
    }
    return path.startsWith("permalink.php") || path.startsWith("story.php") || path.startsWith("photo") || path.startsWith("watch") || path.startsWith("reel/") || path.matches("[a-zA-Z0-9-._]*/posts.*") || path.matches("groups/[a-zA-Z0-9-._]*/posts.*");
  }

  private String firstMeta(JsonNode node, String property) {
    return "";
  }

  private String findSingleImage(Document document) {
    for (JsonNode block : jsonSearch.jsonBlocks(document)) {
      if (jsonSearch.has(block, "prefetch_uris_v2")) {
        JsonNode list = jsonSearch.first(block, "prefetch_uris_v2");
        if (list != null && list.isArray() && !list.isEmpty()) {
          String uri = textOr(list.get(0), "uri");
          if (!uri.isBlank()) {
            return uri;
          }
        }
      }
    }
    throw new ParseException("cannot find single image");
  }

  private String textOr(JsonNode node, String... path) {
    JsonNode current = node;
    if (current == null) {
      return "";
    }
    for (String part : path) {
      if (current == null) {
        return "";
      }
      if (current.isArray()) {
        try {
          int index = Integer.parseInt(part);
          current = current.size() > index ? current.get(index) : null;
          continue;
        } catch (NumberFormatException ignored) {
          return "";
        }
      }
      if (!current.isObject()) {
        return "";
      }
      current = current.get(part);
    }
    return current == null || current.isNull() ? "" : current.asText("");
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String blankToZero(String value) {
    return value == null || value.isBlank() ? "0" : value;
  }

  private String stripLeadingSlash(String path) {
    return path == null ? "" : path.replaceFirst("^/", "");
  }

  private String sanitize(String value) {
    return value == null ? "" : value.replaceAll("[^a-zA-Z0-9]", "_").substring(0, Math.min(80, value.replaceAll("[^a-zA-Z0-9]", "_").length()));
  }

  private record Story(String authorName, String authorId, String text, String url, List<String> imageLinks, List<String> videoLinks) {}

  private record Counts(String likes, String comments, String shares) {
    boolean isEmpty() {
      return "0".equals(likes) && "0".equals(comments) && "0".equals(shares);
    }
  }

  private record FetchedPage(Document document, String rawHtml, String finalUrl) {}

  private static class FacebedException extends RuntimeException {
    FacebedException(String message) {
      super(message);
    }

    FacebedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class NoDataException extends FacebedException {
    NoDataException(String message) {
      super(message);
    }
  }

  private static final class ParseException extends FacebedException {
    ParseException(String message) {
      super(message);
    }

    ParseException(String message, String html, String url, Throwable cause) {
      super(message + " @ " + url, cause);
    }
  }
}
