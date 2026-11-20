package dev.dineshrama.tracelens;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class RecoveryPolicy {
  private RecoveryPolicy() {}
  public record Decision(String action, String explanation, boolean replayEligible, List<String> missingEvidence) {}

  public static Decision assess(JsonNode snapshot) {
    var event=snapshot.path("event");var ledger=snapshot.path("ledger");
    if(!event.isObject())return new Decision("REQUEST_EVIDENCE","Event state is unavailable. No recovery decision can be made.",false,List.of("Current event state"));
    if(!ledger.path("known").asBoolean())return new Decision("REQUEST_EVIDENCE","Unknown ledger state is not proof that no credit was applied.",false,List.of("Authoritative ledger snapshot"));
    if(snapshot.path("integrityConflict").asBoolean())return new Decision("INVESTIGATE_CONFLICT","The captured sources disagree. Resolve the snapshot conflict before recovery.",false,List.of("Consistent event and ledger snapshot"));
    var entries=ledger.path("entries");
    if(!entries.isArray())return new Decision("REQUEST_EVIDENCE","A complete ledger result is required.",false,List.of("Complete ledger query result"));
    if(entries.size()>0) {
      boolean mismatch=entries.size()!=1 || !entries.get(0).path("event_id").asText().equals(event.path("id").asText())
          || entries.get(0).path("credits").asInt()!=event.path("credits").asInt()
          || !entries.get(0).path("account_id").asText().equals(event.path("account_id").asText());
      if(mismatch)return new Decision("INVESTIGATE_CONFLICT","Ledger identity or amount does not match the event.",false,List.of("Verified ledger integrity"));
      return new Decision(event.path("status").asText().equals("SUCCEEDED")?"NO_ACTION":"RECONCILE_STATUS","A committed ledger entry exists. Never grant these credits again.",false,List.of());
    }
    return switch(event.path("status").asText()) {
      case "RETRY_WAIT","RECEIVED" -> new Decision("WAIT_FOR_WORKER","The durable event is queued for processing. Manual replay is unnecessary.",false,List.of());
      case "PROCESSING" -> new Decision(event.path("lease_expired").asBoolean()?"RECOVER_LEASE":"WAIT_FOR_WORKER","No credit effect is recorded. The worker lease governs recovery.",false,List.of());
      case "DEAD_LETTER" -> event.path("fault_mode").asText("NONE").equals("NONE")
          ?new Decision("PROPOSE_REPLAY","The event is dead-lettered, the ledger is empty, and the injected fault has been cleared. An operator may review a replay.",true,List.of())
          :new Decision("CORRECT_MAPPING","Correct the terminal condition before asking an operator to replay this event.",false,List.of());
      default -> new Decision("REQUEST_EVIDENCE","The event status and ledger are inconsistent.",false,List.of("Consistent event and ledger state"));
    };
  }

  // This validates structured classifications against captured invariants, not the semantic truth of arbitrary prose.
  public static Set<String> compatibleDiagnoses(JsonNode snapshot, Decision policy) {
    if(policy.action().equals("REQUEST_EVIDENCE"))return Set.of("INSUFFICIENT_EVIDENCE");
    if(policy.action().equals("INVESTIGATE_CONFLICT"))return Set.of("INTEGRITY_CONFLICT","INSUFFICIENT_EVIDENCE");
    if(policy.action().equals("RECONCILE_STATUS"))return Set.of("ACK_LOST");
    if(policy.action().equals("NO_ACTION"))return Set.of(snapshot.path("event").path("delivery_count").asInt()>1?"DUPLICATE_PROTECTED":"COMPLETED");
    if(policy.action().equals("CORRECT_MAPPING")||policy.action().equals("PROPOSE_REPLAY"))return Set.of("POISON_EVENT");
    if(policy.action().equals("RECOVER_LEASE"))return Set.of("STALE_WORKER");
    return Set.of("TRANSIENT_TIMEOUT","PENDING_WORK");
  }

  public static Set<String> allowedAdvice(Decision policy) {
    return switch(policy.action()) {
      case "REQUEST_EVIDENCE" -> Set.of("REQUEST_LEDGER","CONTACT_OPERATOR");
      case "INVESTIGATE_CONFLICT" -> Set.of("REQUEST_LEDGER","CONTACT_OPERATOR","COMPARE_LEDGER");
      case "RECONCILE_STATUS" -> Set.of("COMPARE_LEDGER","CONTACT_OPERATOR");
      case "NO_ACTION" -> Set.of("COMPARE_LEDGER");
      case "CORRECT_MAPPING","PROPOSE_REPLAY" -> Set.of("CORRECT_MAPPING","CONTACT_OPERATOR");
      case "RECOVER_LEASE" -> Set.of("CONTACT_OPERATOR","COMPARE_LEDGER");
      default -> Set.of("WAIT_FOR_RETRY","CHECK_DEPENDENCY");
    };
  }
}
