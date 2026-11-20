package dev.dineshrama.tracelens;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApiController {
  private final Workspace workspace;
  private final Investigations investigations;
  private final HybridRetriever retrieval;
  private final WebhookImporter importer;
  private final TraceProperties config;
  public ApiController(Workspace workspace,Investigations investigations,HybridRetriever retrieval,WebhookImporter importer,TraceProperties config) {
    this.workspace=workspace;this.investigations=investigations;this.retrieval=retrieval;this.importer=importer;this.config=config;
  }
  @GetMapping("/api/config") public Object config() {
    return Map.of("mode","live","chatModel",config.chatModel(),"embeddingModel",config.embeddingModel(),"workerEnabled",config.workerEnabled(),"maxLlmCalls",3,"readOnlyTools",true,"webhookImportConfigured",config.webhookLabUrl()!=null&&!config.webhookLabUrl().isBlank());
  }
  @GetMapping("/api/incidents") public Object incidents(){return workspace.incidents();}
  @GetMapping("/api/incidents/{id}") public Object incident(@PathVariable String id){return workspace.incident(id);}
  @GetMapping("/api/runbooks") public Object runbooks(){return workspace.runbooks();}
  @GetMapping("/api/investigations") public Object history(){return investigations.history();}
  @GetMapping("/api/investigations/{id}") public Object detail(@PathVariable UUID id){return investigations.detail(id);}
  public record InvestigationRequest(String incidentId,String question,String requestKey) {}
  @PostMapping("/api/investigations") public Object investigate(@RequestBody InvestigationRequest request) {
    var receipt=investigations.submit(request.incidentId(),request.question(),request.requestKey());
    return ResponseEntity.status(receipt.duplicate()?200:202).body(receipt);
  }
  @PostMapping("/api/worker/tick") public Object tick(){return Map.of("claimed",investigations.tick());}
  public record SearchRequest(String query) {}
  @PostMapping("/api/retrieval") public Object search(@RequestBody SearchRequest request) {
    if(request.query()==null||request.query().isBlank()||request.query().length()>240)throw new Problem(400,"INVALID_QUERY","Supply a runbook query of 1–240 characters.");
    return retrieval.search(Redactor.clean(request.query()),3);
  }
  public record ImportRequest(String eventId) {}
  @PostMapping("/api/import/webhook") public Object capture(@RequestBody ImportRequest request){return importer.capture(request.eventId());}
}
