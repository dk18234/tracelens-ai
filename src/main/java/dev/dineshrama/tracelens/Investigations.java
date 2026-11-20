package dev.dineshrama.tracelens;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Investigations {
  private final JdbcTemplate db;
  private final Workspace workspace;
  private final TransactionTemplate tx;
  private final InvestigationPipeline pipeline;
  private final TraceProperties config;
  private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
  public Investigations(JdbcTemplate db, Workspace workspace, TransactionTemplate tx, InvestigationPipeline pipeline, TraceProperties config) {
    this.db=db;this.workspace=workspace;this.tx=tx;this.pipeline=pipeline;this.config=config;
  }
  public record Receipt(String id,String status,boolean duplicate) {}
  public Receipt submit(String incidentId,String question,String key) {
    if(incidentId==null||question==null||question.isBlank()||question.length()>600||key==null||!key.matches("[a-zA-Z0-9_-]{1,100}"))throw new Problem(400,"INVALID_REQUEST","Supply an incident ID, a question of 1–600 characters, and a request key.");
    var incident=workspace.incident(incidentId);
    String fingerprint=Json.hash(incidentId+"\n"+incident.get("revision")+"\n"+question);
    return tx.execute(status->{
      UUID id=UUID.randomUUID();
      int inserted=db.update("INSERT INTO investigations(id,request_key,fingerprint,incident_id,incident_revision,snapshot,question) VALUES(?,?,?,?,?,?::jsonb,?) ON CONFLICT(request_key) DO NOTHING",id,key,fingerprint,incidentId,incident.get("revision"),Json.write(incident.get("snapshot")),Redactor.clean(question));
      var row=db.queryForMap("SELECT id,status,fingerprint FROM investigations WHERE request_key=?",key);
      if(!row.get("fingerprint").equals(fingerprint))throw new Problem(409,"REQUEST_KEY_CONFLICT","This request key belongs to a different question or incident revision.");
      return new Receipt(row.get("id").toString(),row.get("status").toString(),inserted==0);
    });
  }
  @Scheduled(fixedDelay=1000) public void poll() {
    if(!config.workerEnabled())return;
    try { tick(); } catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("Investigation worker could not claim work: {}",e.getClass().getSimpleName()); }
  }
  public boolean tick() {
    if (!busy.compareAndSet(false, true)) return false;
    try { return claimAndRun(); } finally { busy.set(false); }
  }
  private boolean claimAndRun() {
    Map<String,Object> claimed=tx.execute(status->{
      db.update("UPDATE investigations SET status='FAILED',error_code='WORKER_INTERRUPTED',error_message='The worker lease expired. Start a new investigation; no model call is automatically replayed.',completed_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE status='RUNNING' AND lease_until<=clock_timestamp()");
      var rows=db.queryForList("SELECT id,snapshot,question FROM investigations WHERE status='QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
      if(rows.isEmpty())return null;
      var job=new LinkedHashMap<>(rows.getFirst());UUID token=UUID.randomUUID();job.put("token",token);
      db.update("UPDATE investigations SET status='RUNNING',lease_token=?,lease_until=clock_timestamp()+(?*interval '1 second') WHERE id=?",token,config.leaseSeconds(),job.get("id"));
      return job;
    });
    if(claimed==null)return false;
    try {
      var result=pipeline.investigate(Json.read(claimed.get("snapshot").toString()),claimed.get("question").toString());
      String state=((JsonNode)result.get("report")).path("diagnosisCode").asText().equals("INSUFFICIENT_EVIDENCE")?"NEEDS_EVIDENCE":"COMPLETED";
      db.update("UPDATE investigations SET status=?,result=?::jsonb,completed_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND lease_until>clock_timestamp()",state,Json.write(result),claimed.get("id"),claimed.get("token"));
    } catch(Exception error) {
      Problem p=error instanceof Problem known?known:new Problem(500,"INVESTIGATION_FAILED","The investigation could not complete. No recovery action was executed.");
      db.update("UPDATE investigations SET status='FAILED',error_code=?,error_message=?,completed_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",p.code,p.getMessage(),claimed.get("id"),claimed.get("token"));
    }
    return true;
  }
  public Map<String,Object> detail(UUID id) {
    var rows=db.queryForList("SELECT id,request_key,incident_id,incident_revision,question,status,result,error_code,error_message,created_at,completed_at FROM investigations WHERE id=?",id);
    if(rows.isEmpty())throw new Problem(404,"INVESTIGATION_NOT_FOUND","Investigation not found.");
    var row=new LinkedHashMap<>(rows.getFirst());
    if(row.get("result")!=null)row.put("result",Json.read(row.get("result").toString()));
    return row;
  }
  public List<Map<String,Object>> history() {
    return db.queryForList("SELECT id,incident_id,incident_revision,status,created_at,completed_at FROM investigations ORDER BY created_at DESC LIMIT 50");
  }
}
