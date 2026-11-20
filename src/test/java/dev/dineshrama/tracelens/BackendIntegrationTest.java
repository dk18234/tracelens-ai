package dev.dineshrama.tracelens;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"trace.worker-enabled=false","trace.model-timeout-seconds=2"})
class BackendIntegrationTest {
  static final EmbeddedPostgres pg;
  static final HttpServer fake;
  static final AtomicReference<String> mode=new AtomicReference<>("valid");
  static final AtomicInteger chatCalls=new AtomicInteger();
  static {
    try {
      pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("unix_socket_directories","").start();
      fake=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      fake.createContext("/api/embed",exchange->{
        var body=Json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
        List<Object> rows=new ArrayList<>();for(var input:body.path("input"))rows.add(mode.get().equals("zero-embedding")?List.of(0.0,0.0,0.0):List.of(1.0,0.5,0.25));
        if(mode.get().equals("embedding-count"))rows.clear();
        byte[] bytes=Json.write(Map.of("embeddings",rows)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
      });
      fake.createContext("/api/chat",exchange->{
        chatCalls.incrementAndGet();var request=Json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
        boolean plan=request.path("format").path("properties").has("tools");
        String content;
        if(plan)content=Json.write(Map.of("tools",List.of(mode.get().equals("unsafe-tool")?"delete_ledger":"get_ledger","get_event"),"runbookQuery","committed completion acknowledgement ledger"));
        else content=Json.write(Map.of("diagnosisCode",mode.get().equals("contradiction")?"TRANSIENT_TIMEOUT":"ACK_LOST","summary","A committed credit is waiting for completion reconciliation.",
            "findings",List.of(Map.of("text","The captured ledger contains one effect.","evidenceIds",List.of(mode.get().equals("citation")?"E999":"E3"))),"missingEvidence",List.of(),"nextSteps",List.of("COMPARE_LEDGER")));
        if(!plan&&mode.get().equals("unsafe-prose"))content=content.replace("A committed credit is waiting for completion reconciliation.","The event is safe to replay.");
        if(mode.get().equals("malformed"))content="not JSON";
        if(mode.get().equals("empty"))content="";
        byte[] bytes=Json.write(Map.of("message",Map.of("content",content),"prompt_eval_count",11,"eval_count",7)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
      });
      fake.start();
    } catch(Exception e){throw new ExceptionInInitializerError(e);}
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->pg.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
    r.add("trace.ollama-url",()->"http://127.0.0.1:"+fake.getAddress().getPort());
  }
  @Autowired Investigations jobs;
  @Autowired Workspace workspace;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @Autowired TraceProperties config;
  @BeforeEach void reset(){db.execute("TRUNCATE investigations");mode.set("valid");chatCalls.set(0);}
  @AfterAll static void stop() throws Exception{fake.stop(0);pg.close();}
  Investigations.Receipt submit(String key){return jobs.submit("inc_ack_lost","Why is completion missing?",key);}
  @Test void completePipelineUsesPersistedEvidenceAndRealSqlRetrieval() {
    var r=submit("req_full");assertThat(r.status()).isEqualTo("QUEUED");jobs.tick();
    var result=jobs.detail(UUID.fromString(r.id()));assertThat(result.get("status")).isEqualTo("COMPLETED");
    var data=(com.fasterxml.jackson.databind.JsonNode)result.get("result");
    assertThat(data.path("report").path("diagnosisCode").asText()).isEqualTo("ACK_LOST");
    assertThat(data.path("policy").path("replayEligible").asBoolean()).isFalse();
    assertThat(data.path("retrieval").path("hits").size()).isEqualTo(3);
    assertThat(data.path("usage").path("llmCalls").asInt()).isEqualTo(2);
    assertThat(data.path("checks").path("semanticEntailment").asText()).isEqualTo("not-proven");
  }
  @Test void repeatedRequestKeyDoesNotMakeAnotherModelCall() {
    var first=submit("req_same");var second=submit("req_same");assertThat(second.id()).isEqualTo(first.id());assertThat(second.duplicate()).isTrue();
    jobs.tick();jobs.tick();assertThat(chatCalls.get()).isEqualTo(2);
  }
  @Test void concurrentSubmissionsDeduplicateBeforeModelWork() throws Exception {
    try(var pool=Executors.newFixedThreadPool(8)) {
      var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Investigations.Receipt>>();
      for(int i=0;i<24;i++)futures.add(pool.submit(()->{gate.await();return submit("req_concurrent");}));
      gate.countDown();var identities=new HashSet<String>();for(var f:futures)identities.add(f.get(10,TimeUnit.SECONDS).id());assertThat(identities).hasSize(1);
    }
    assertThat(db.queryForObject("SELECT count(*) FROM investigations",Integer.class)).isOne();
  }
  @Test void conflictingQuestionWithSameKeyIsRejected(){submit("req_conflict");assertThatThrownBy(()->jobs.submit("inc_ack_lost","Another question","req_conflict")).isInstanceOf(Problem.class);}
  @Test void inventedReferencesAreRejectedAfterBoundedRepair(){mode.set("citation");var r=submit("req_cite");jobs.tick();var d=jobs.detail(UUID.fromString(r.id()));assertThat(d.get("error_code")).isEqualTo("UNKNOWN_CITATION");assertThat(d.get("result")).isNull();assertThat(chatCalls.get()).isEqualTo(3);}
  @Test void contradictoryModelAnswerIsNotExposedAsADiagnosis(){mode.set("contradiction");var r=submit("req_contradict");jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("error_code")).isEqualTo("MODEL_CONTRADICTS_FACTS");}
  @Test void unapprovedToolDoesNotReachExecution(){mode.set("unsafe-tool");var r=submit("req_tool");jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("error_code")).isEqualTo("TOOL_NOT_ALLOWED");assertThat(chatCalls.get()).isOne();}
  @Test void unsafeWrittenAdviceIsRejectedAfterBoundedRepair(){mode.set("unsafe-prose");var r=submit("req_prose");jobs.tick();var d=jobs.detail(UUID.fromString(r.id()));assertThat(d.get("error_code")).isEqualTo("UNSAFE_REPLAY_PROSE");assertThat(d.get("result")).isNull();assertThat(chatCalls.get()).isEqualTo(3);}
  @Test void malformedModelResponseFailsWithoutFabricatingAReport(){mode.set("malformed");var r=submit("req_bad");jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("error_code")).isEqualTo("MODEL_OUTPUT_INVALID");}
  @Test void emptyModelResponseDoesNotBecomeAReport(){mode.set("empty");var r=submit("req_empty");jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("error_code")).isEqualTo("MODEL_OUTPUT_INVALID");}
  @Test void zeroVectorsCannotSilentlyPolluteRetrieval(){mode.set("zero-embedding");var r=submit("req_zero");jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("error_code")).isEqualTo("EMBEDDING_INVALID");}
  @Test void mismatchedEmbeddingBatchIsRejected(){mode.set("embedding-count");var r=submit("req_count");jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("error_code")).isEqualTo("EMBEDDING_INVALID");}
  @Test void webhookImportUsesOnlyGetAndCreatesRedactedRevisions() throws Exception {
    var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var methods=new ArrayList<String>();var full=new AtomicBoolean(true);
    upstream.createContext("/api/events/evt_import",exchange->{
      methods.add(exchange.getRequestMethod());assertThat(exchange.getRequestHeaders().getFirst("X-Admin-Token")).isEqualTo("upstream-test-token");
      var body=new LinkedHashMap<String,Object>();body.put("event",Map.of("id","evt_import","account_id","acct_demo","credits",10,"status","PROCESSING","lease_until","2020-01-01T00:00:00Z","last_error","Bearer sensitive-secret-token"));
      body.put("attempts",List.of());body.put("activity",List.of());if(full.get())body.put("ledger",List.of());
      byte[] bytes=Json.write(body).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });upstream.start();
    try {
      var p=new TraceProperties(config.adminToken(),config.ollamaUrl(),config.chatModel(),config.embeddingModel(),2,false,600,"http://127.0.0.1:"+upstream.getAddress().getPort(),"upstream-test-token");
      var importer=new WebhookImporter(p,db);importer.capture("evt_import");
      var first=workspace.incident("live_evt_import");var snapshot=(com.fasterxml.jackson.databind.JsonNode)first.get("snapshot");
      assertThat(snapshot.path("ledger").path("known").asBoolean()).isTrue();assertThat(snapshot.path("event").path("lease_expired").asBoolean()).isTrue();assertThat(snapshot.toString()).doesNotContain("sensitive-secret-token");
      full.set(false);importer.capture("evt_import");var second=workspace.incident("live_evt_import");
      assertThat(((Number)second.get("revision")).longValue()).isEqualTo(((Number)first.get("revision")).longValue()+1);
      assertThat(((com.fasterxml.jackson.databind.JsonNode)second.get("snapshot")).path("ledger").path("known").asBoolean()).isFalse();
      assertThat(methods).containsExactly("GET","GET");
    } finally {upstream.stop(0);}
  }
  @Test void expiredRunningJobFailsExplicitlyWithoutRepeatingModelCost(){var r=submit("req_expired");db.update("UPDATE investigations SET status='RUNNING',lease_token=?,lease_until=clock_timestamp()-interval '1 second'",UUID.randomUUID());jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("error_code")).isEqualTo("WORKER_INTERRUPTED");assertThat(chatCalls.get()).isZero();}
  @Test void capturedRevisionSurvivesNewIncidentData(){var r=submit("req_revision");db.update("UPDATE incidents SET revision=revision+1 WHERE id='inc_ack_lost'");jobs.tick();assertThat(jobs.detail(UUID.fromString(r.id())).get("status")).isEqualTo("COMPLETED");}
  @Test void authenticationAndBoundedBodiesAreEnforced(){assertThat(http.getForEntity("/api/incidents",String.class).getStatusCode().value()).isEqualTo(401);var h=headers();assertThat(http.postForEntity("/api/investigations",new HttpEntity<>("x".repeat(16385),h),String.class).getStatusCode().value()).isEqualTo(413);}
  @Test void invalidImportIdAndUnconfiguredUpstreamAreClientErrors(){var h=headers();assertThat(http.postForEntity("/api/import/webhook",new HttpEntity<>("{\"eventId\":\"../secret\"}",h),String.class).getStatusCode().value()).isEqualTo(400);assertThat(http.postForEntity("/api/import/webhook",new HttpEntity<>("{\"eventId\":\"evt_demo\"}",h),String.class).getStatusCode().value()).isEqualTo(422);}
  @Test void schemaValidationErrorsAreUseful(){var h=headers();assertThat(http.postForEntity("/api/investigations",new HttpEntity<>("{}",h),String.class).getStatusCode().value()).isEqualTo(400);assertThat(http.getForEntity("/actuator/health",String.class).getStatusCode().value()).isEqualTo(200);}
  private HttpHeaders headers(){var h=new HttpHeaders();h.set("X-Admin-Token",config.adminToken());h.setContentType(MediaType.APPLICATION_JSON);return h;}
}
