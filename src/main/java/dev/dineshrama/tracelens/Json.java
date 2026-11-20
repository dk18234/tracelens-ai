package dev.dineshrama.tracelens;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class Json {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private Json() {}
  public static String write(Object value) {
    try { return MAPPER.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalArgumentException("Cannot encode value", e); }
  }
  public static JsonNode read(String value) {
    try { return MAPPER.readTree(value); }
    catch (Exception e) { throw new Problem(502, "MODEL_OUTPUT_INVALID", "The model returned invalid JSON."); }
  }
  public static String hash(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
  }
}
