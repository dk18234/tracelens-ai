package dev.dineshrama.tracelens;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OllamaClient {
  private final TraceProperties config;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  public record Completion(JsonNode value, long inputTokens, long outputTokens, long milliseconds) {}
  public OllamaClient(TraceProperties config) { this.config = config; }

  public Completion complete(String system, String user, Map<String,Object> schema, int maxTokens) {
    long start = System.nanoTime();
    var body = Map.of("model", config.chatModel(), "messages", List.of(
        Map.of("role", "system", "content", system + "\nRequired JSON schema:\n" + Json.write(schema)), Map.of("role", "user", "content", user)),
        "stream", false, "format", schema, "keep_alive", "10m",
        "options", Map.of("temperature", 0, "seed", 42, "num_predict", maxTokens, "num_ctx", 4096));
    JsonNode result = post("/api/chat", body);
    String content = result.path("message").path("content").asText();
    if (content.isBlank()) throw new Problem(502, "MODEL_OUTPUT_INVALID", "The language model returned an empty answer.");
    return new Completion(Json.read(content), result.path("prompt_eval_count").asLong(), result.path("eval_count").asLong(), (System.nanoTime()-start)/1_000_000);
  }

  public List<double[]> embed(List<String> texts) {
    if (texts.isEmpty() || texts.size()>32) throw new IllegalArgumentException("Embedding batch size must be 1..32.");
    JsonNode response = post("/api/embed", Map.of("model", config.embeddingModel(), "input", texts, "truncate", false, "keep_alive", "10m"));
    JsonNode rows = response.path("embeddings");
    if (!rows.isArray() || rows.size()!=texts.size()) throw new Problem(502, "EMBEDDING_INVALID", "Embedding count did not match the request.");
    List<double[]> vectors = new ArrayList<>();
    int dimension = -1;
    for (JsonNode row : rows) {
      if (!row.isArray() || row.size()<2 || row.size()>4096 || (dimension!=-1 && row.size()!=dimension)) throw new Problem(502, "EMBEDDING_INVALID", "Embedding dimensions are invalid.");
      dimension = row.size();
      double[] vector = new double[dimension];
      for (int i=0; i<dimension; i++) {
        if (!row.get(i).isNumber() || !Double.isFinite(row.get(i).asDouble())) throw new Problem(502, "EMBEDDING_INVALID", "Embedding contains a non-finite value.");
        vector[i] = row.get(i).asDouble();
      }
      if (Arrays.stream(vector).map(v->v*v).sum()==0) throw new Problem(502, "EMBEDDING_INVALID", "Zero embedding is not usable.");
      vectors.add(vector);
    }
    return vectors;
  }

  private JsonNode post(String path, Object body) {
    try {
      URI base = URI.create(config.ollamaUrl());
      if (!Set.of("http", "https").contains(base.getScheme()) || base.getUserInfo()!=null) throw new IllegalArgumentException("Invalid configured model URL");
      var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(config.modelTimeoutSeconds()))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        byte[] bytes = stream.readNBytes(1_048_577);
        if (response.statusCode()!=200) throw new Problem(502, "MODEL_UNAVAILABLE", "Ollama could not complete the request. Confirm both configured models are installed.");
        if (bytes.length>1_048_576) throw new Problem(502, "MODEL_RESPONSE_TOO_LARGE", "The model response exceeded its size limit.");
        return Json.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      }
    } catch (Problem e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Problem(503, "MODEL_INTERRUPTED", "The model request was interrupted."); }
    catch (Exception e) { throw new Problem(503, "MODEL_UNAVAILABLE", "Cannot reach the local model service. Start Ollama and pull the configured models."); }
  }
}
