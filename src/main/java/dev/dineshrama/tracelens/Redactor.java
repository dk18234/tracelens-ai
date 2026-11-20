package dev.dineshrama.tracelens;

import java.util.regex.Pattern;

public final class Redactor {
  private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]{6,}");
  private static final Pattern SECRET = Pattern.compile("(?i)((?:api[_-]?key|password|secret|token)\\s*[:=]\\s*\\\"?)[^\\s\\\",}]{4,}");
  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private Redactor() {}
  public static String clean(String text) {
    String safe = text == null ? "" : text;
    safe = BEARER.matcher(safe).replaceAll("$1[REDACTED]");
    safe = SECRET.matcher(safe).replaceAll("$1[REDACTED]");
    return EMAIL.matcher(safe).replaceAll("[REDACTED_EMAIL]");
  }
}
