package dev.dineshrama.tracelens;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Workspace implements ApplicationRunner {
  private final JdbcTemplate db;
  public Workspace(JdbcTemplate db) { this.db = db; }
  @Override public void run(ApplicationArguments args) throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/fixtures/incidents.json")) {
      for (JsonNode item : Json.read(new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))) {
        db.update("INSERT INTO incidents(id,title,severity,summary,snapshot) VALUES(?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
            item.path("id").asText(), item.path("title").asText(), item.path("severity").asText(), item.path("summary").asText(), Json.write(item.path("snapshot")));
      }
    }
    try (InputStream stream = getClass().getResourceAsStream("/fixtures/runbooks.json")) {
      for (JsonNode item : Json.read(new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))) {
        String body = item.path("body").asText();
        String title = item.path("title").asText();
        db.update("INSERT INTO runbooks(id,title,body,content_hash) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=EXCLUDED.title,body=EXCLUDED.body,content_hash=EXCLUDED.content_hash",
            item.path("id").asText(), title, body, documentHash(title,body));
      }
    }
  }
  static String documentHash(String title,String body) { return Json.hash(title+"\n"+body); }
  public List<Map<String,Object>> incidents() {
    return db.queryForList("SELECT id,title,severity,summary,revision,source,updated_at FROM incidents ORDER BY id");
  }
  public Map<String,Object> incident(String id) {
    var rows = db.queryForList("SELECT * FROM incidents WHERE id=?", id);
    if (rows.isEmpty()) throw new Problem(404, "INCIDENT_NOT_FOUND", "Incident not found.");
    var result = new LinkedHashMap<>(rows.getFirst());
    result.put("snapshot", Json.read(result.get("snapshot").toString()));
    return result;
  }
  public List<Map<String,Object>> runbooks() {
    return db.queryForList("SELECT id,title,body,content_hash FROM runbooks ORDER BY id");
  }
  public List<Map<String,Object>> lexical(String query) {
    return db.queryForList("SELECT id,title,body,ts_rank_cd(search_vector,websearch_to_tsquery('english',?)) AS score FROM runbooks WHERE search_vector @@ websearch_to_tsquery('english',?) ORDER BY score DESC,id LIMIT 6", query, query);
  }
}
