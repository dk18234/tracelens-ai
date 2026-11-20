package dev.dineshrama.tracelens;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyAndContractTest {
  JsonNode fixture(String id) throws Exception {
    var bytes=getClass().getResourceAsStream("/fixtures/incidents.json").readAllBytes();
    for(var row:Json.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)))if(row.path("id").asText().equals(id))return row.path("snapshot").deepCopy();
    throw new IllegalArgumentException();
  }
  JsonNode report(String code) {
    return Json.read(Json.write(Map.of("diagnosisCode",code,"summary","The ledger contains a committed effect.","findings",List.of(Map.of("text","One credit entry is present.","evidenceIds",List.of("E3"))),"missingEvidence",List.of(),"nextSteps",List.of("COMPARE_LEDGER"))));
  }
  @Test void committedEffectBlocksReplayAndRequestsReconciliation() throws Exception {
    var d=RecoveryPolicy.assess(fixture("inc_ack_lost"));assertThat(d.action()).isEqualTo("RECONCILE_STATUS");assertThat(d.replayEligible()).isFalse();
  }
  @Test void duplicateDeliveryIsAlreadyCompleted() throws Exception {
    var d=RecoveryPolicy.assess(fixture("inc_duplicates"));assertThat(d.action()).isEqualTo("NO_ACTION");assertThat(d.replayEligible()).isFalse();
  }
  @Test void unknownLedgerCannotBeTreatedAsZeroRows() throws Exception {
    var d=RecoveryPolicy.assess(fixture("inc_missing"));assertThat(d.action()).isEqualTo("REQUEST_EVIDENCE");assertThat(d.missingEvidence()).isNotEmpty();
  }
  @Test void activePoisonFaultMustBeCorrectedBeforeReplay() throws Exception {
    var d=RecoveryPolicy.assess(fixture("inc_poison"));assertThat(d.action()).isEqualTo("CORRECT_MAPPING");assertThat(d.replayEligible()).isFalse();
  }
  @Test void correctedDeadLetterCanOnlyProduceAnOperatorProposal() throws Exception {
    var data=fixture("inc_poison");((ObjectNode)data.path("event")).put("fault_mode","NONE");
    var d=RecoveryPolicy.assess(data);assertThat(d.action()).isEqualTo("PROPOSE_REPLAY");assertThat(d.replayEligible()).isTrue();
  }
  @Test void expiredUncommittedWorkerUsesLeaseRecovery() throws Exception {
    assertThat(RecoveryPolicy.assess(fixture("inc_stale")).action()).isEqualTo("RECOVER_LEASE");
  }
  @Test void conflictingAmountsBlockRecovery() throws Exception {
    assertThat(RecoveryPolicy.assess(fixture("inc_conflict")).action()).isEqualTo("INVESTIGATE_CONFLICT");
  }
  @Test void missingEventAbstains() {
    assertThat(RecoveryPolicy.assess(Json.read("{}")).action()).isEqualTo("REQUEST_EVIDENCE");
  }
  @Test void promptInjectionCannotChangeDeterministicEligibility() throws Exception {
    var d=RecoveryPolicy.assess(fixture("inc_injection"));assertThat(d.action()).isEqualTo("RECONCILE_STATUS");assertThat(d.replayEligible()).isFalse();
  }
  @Test void inventedCitationIsRejected() {
    var r=report("ACK_LOST");((ObjectNode)r.path("findings").get(0)).putArray("evidenceIds").add("E999");
    assertThatThrownBy(()->ModelContracts.validateReport(r,Set.of("E1","E3"),Set.of("ACK_LOST"),false)).isInstanceOf(Problem.class).hasMessageContaining("outside");
  }
  @Test void unsupportedActionIsRejected() {
    var r=report("ACK_LOST");((ObjectNode)r).putArray("nextSteps").add("DELETE_LEDGER");
    assertThatThrownBy(()->ModelContracts.validateReport(r,Set.of("E3"),Set.of("ACK_LOST"),false)).isInstanceOf(Problem.class).hasMessageContaining("unsupported");
  }
  @Test void conflictingClassificationIsRejectedEvenWithValidCitations() {
    assertThatThrownBy(()->ModelContracts.validateReport(report("TRANSIENT_TIMEOUT"),Set.of("E3"),Set.of("ACK_LOST"),false)).isInstanceOf(Problem.class).hasMessageContaining("contradicts");
  }
  @Test void missingEvidenceMustBeNamedInAnAbstention() {
    assertThatThrownBy(()->ModelContracts.validateReport(report("INSUFFICIENT_EVIDENCE"),Set.of("E3"),Set.of("INSUFFICIENT_EVIDENCE"),true)).isInstanceOf(Problem.class);
  }
  @Test void arbitraryOrDuplicateToolsAreNotExecutable() {
    for(String value:List.of("{\"tools\":[\"delete_ledger\"],\"runbookQuery\":\"lease\"}","{\"tools\":[\"get_event\",\"get_event\"],\"runbookQuery\":\"lease\"}"))
      assertThatThrownBy(()->ModelContracts.validatePlan(Json.read(value))).isInstanceOf(Problem.class);
  }
  @Test void redactionCoversBearerTokensEmailsAndSecretAssignments() {
    String safe=Redactor.clean("Bearer sk_1234567890 api_key=abc123456 password=hunter22 person@example.org");
    assertThat(safe).doesNotContain("sk_1234567890","abc123456","hunter22","person@example.org");assertThat(safe).contains("REDACTED");
  }
  @Test void cosineHandlesOrthogonalAndIdenticalVectors() {
    assertThat(HybridRetriever.cosine(new double[]{1,0},new double[]{0,1})).isZero();
    assertThat(HybridRetriever.cosine(new double[]{1,2},new double[]{1,2})).isCloseTo(1.0,within(.00001));
    assertThatThrownBy(()->HybridRetriever.cosine(new double[]{1},new double[]{1,2})).isInstanceOf(Problem.class);
  }
}
