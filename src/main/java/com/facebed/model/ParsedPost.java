package com.facebed.model;

import java.util.List;

public record ParsedPost(
    String authorName,
    String text,
    List<String> imageLinks,
    String url,
    long date,
    String likes,
    String comments,
    String shares,
    List<String> videoLinks
) {}
