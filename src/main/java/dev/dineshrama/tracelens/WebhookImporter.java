package dev.dineshrama.tracelens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebhookImporter {
  private final TraceProperties config;
  private final JdbcTemplate db;
  public WebhookImporter(TraceProperties config,JdbcTemplate db) { this.config=config;this.db=db; }
  public Map<String,Object> capture(String eventId) {
    if(eventId==null||!eventId.matches("[a-zA-Z0-9_-]{1,80}"))throw new Problem(400,"INVALID_EVENT_ID","Use a valid Webhook Lab event ID.");
    if(config.webhookLabUrl()==null||config.webhookLabUrl().isBlank()||config.webhookAdminToken()==null||config.webhookAdminToken().isBlank())throw new Problem(422,"WEBHOOK_NOT_CONFIGURED","Set WEBHOOK_LAB_URL and WEBHOOK_ADMIN_TOKEN on the server to import a real event.");
    try {
      URI base=URI.create(config.webhookLabUrl());
      if(!java.util.Set.of("http","https").contains(base.getScheme())||base.getUserInfo()!=null)throw new IllegalArgumentException();
      var request=HttpRequest.newBuilder(base.resolve("/api/events/"+eventId)).timeout(Duration.ofSeconds(10)).header("X-Admin-Token",config.webhookAdminToken()).GET().build();
      var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
      var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
      JsonNode source;
      try(var body=response.body()) {
        byte[] bytes=body.readNBytes(65537);
        if(response.statusCode()!=200||bytes.length>65536)throw new Problem(502,"WEBHOOK_READ_FAILED","Could not capture a bounded event snapshot from Webhook Lab.");
        source=Json.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
      }
      ObjectNode event=JsonNodeFactory.instance.objectNode();
      for(String field:new String[]{"id","account_id","credits","status","delivery_count","attempt_count","cycle_attempt","max_attempts","fault_mode","last_error"})event.set(field,source.path("event").path(field));
      if(!eventId.equals(event.path("id").asText()))throw new Problem(502,"WEBHOOK_SNAPSHOT_INVALID","Upstream returned a different event identity.");
      String lease=source.path("event").path("lease_until").asText("");
      boolean expired=false;
      if(!lease.isBlank())try{expired=Instant.parse(lease).isBefore(Instant.now());}catch(Exception ignored) { }
      event.put("lease_expired",expired);
      var snapshot=JsonNodeFactory.instance.objectNode();snapshot.put("capturedAt",Instant.now().toString());snapshot.set("event",event);snapshot.set("attempts",source.path("attempts"));
      var ledger=snapshot.putObject("ledger");ledger.put("known",source.path("ledger").isArray());ledger.set("entries",source.path("ledger"));
      snapshot.set("logs",source.path("activity"));snapshot.put("integrityConflict",false);
      snapshot=(ObjectNode)InvestigationPipeline.redact(snapshot);
      String id="live_"+eventId;
      db.update("INSERT INTO incidents(id,title,severity,summary,snapshot,source) VALUES(?,?,'review',?,?::jsonb,'webhook-lab-readonly') ON CONFLICT(id) DO UPDATE SET snapshot=EXCLUDED.snapshot,revision=incidents.revision+1,updated_at=clock_timestamp()",id,"Live event "+eventId,"Read-only snapshot captured from Webhook Recovery Lab.",Json.write(snapshot));
      return Map.of("incidentId",id,"source","webhook-lab-readonly","upstreamMethod","GET");
    } catch(Problem e){throw e;}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new Problem(503,"WEBHOOK_READ_FAILED","The upstream read was interrupted.");}
    catch(Exception e){throw new Problem(502,"WEBHOOK_READ_FAILED","Could not read the configured Webhook Lab service.");}
  }
}
