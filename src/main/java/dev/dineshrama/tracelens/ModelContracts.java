package dev.dineshrama.tracelens;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class ModelContracts {
  public static final Set<String> TOOLS=Set.of("get_event","get_attempts","get_ledger","get_logs");
  public static final Set<String> DIAGNOSES=Set.of("ACK_LOST","TRANSIENT_TIMEOUT","POISON_EVENT","DUPLICATE_PROTECTED","COMPLETED","STALE_WORKER","PENDING_WORK","INTEGRITY_CONFLICT","INSUFFICIENT_EVIDENCE");
  public static final Set<String> NEXT_STEPS=Set.of("COMPARE_LEDGER","CHECK_DEPENDENCY","CORRECT_MAPPING","WAIT_FOR_RETRY","REQUEST_LEDGER","CONTACT_OPERATOR");
  private ModelContracts() {}
  private static Map<String,Object> text(int max) { return Map.of("type","string","minLength",1,"maxLength",max); }
  private static Map<String,Object> choice(Collection<String> values) { return Map.of("type","string","enum",values.stream().sorted().toList()); }
  private static Map<String,Object> array(Object items,int min,int max) { return Map.of("type","array","items",items,"minItems",min,"maxItems",max); }
  private static Map<String,Object> object(Map<String,Object> properties) { return Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),"additionalProperties",false); }
  public static Map<String,Object> planSchema() {
    return object(Map.of("tools",Map.of("type","array","items",choice(TOOLS),"minItems",1,"maxItems",4,"uniqueItems",true),"runbookQuery",text(200)));
  }
  public static Map<String,Object> reportSchema(Collection<String> evidenceIds) {
    return object(Map.of("diagnosisCode",choice(DIAGNOSES),"summary",text(400),
        "findings",array(object(Map.of("text",text(240),"evidenceIds",array(choice(evidenceIds),1,4))),1,4),
        "missingEvidence",array(text(140),0,4),"nextSteps",array(choice(NEXT_STEPS),1,3)));
  }
  public static Map<String,Object> reportSchema(Collection<String> evidenceIds,Set<String> codes,RecoveryPolicy.Decision policy) {
    var properties=new LinkedHashMap<String,Object>();
    properties.put("diagnosisCode",choice(codes));
    properties.put("summary",text(400));
    properties.put("findings",array(object(Map.of("text",text(240),"evidenceIds",array(choice(evidenceIds),1,4))),1,2));
    properties.put("missingEvidence",array(policy.missingEvidence().isEmpty()?text(140):choice(policy.missingEvidence()),policy.missingEvidence().isEmpty()?0:1,policy.missingEvidence().size()));
    properties.put("nextSteps",array(choice(RecoveryPolicy.allowedAdvice(policy)),1,Math.min(3,RecoveryPolicy.allowedAdvice(policy).size())));
    return object(properties);
  }
  public static void validateAdvice(JsonNode report,RecoveryPolicy.Decision policy) {
    for(var step:report.path("nextSteps"))if(!RecoveryPolicy.allowedAdvice(policy).contains(step.asText()))invalid("ADVICE_CONTRADICTS_POLICY","The proposed advice does not apply to this recovery state.");
    for(var missing:report.path("missingEvidence"))if(!policy.missingEvidence().contains(missing.asText()))invalid("INVENTED_EVIDENCE_GAP","The model invented an unsupported evidence gap.");
  }
  public static void validatePlan(JsonNode value) {
    exactFields(value,Set.of("tools","runbookQuery"));
    if(!value.path("tools").isArray() || value.path("tools").isEmpty() || value.path("tools").size()>4 || !value.path("runbookQuery").isTextual() || value.path("runbookQuery").asText().isBlank() || value.path("runbookQuery").asText().length()>200)
      throw new Problem(502,"MODEL_PLAN_INVALID","The model's tool plan did not satisfy the contract.");
    var seen=new HashSet<String>();
    for(var tool:value.path("tools"))if(!tool.isTextual()||!TOOLS.contains(tool.asText())||!seen.add(tool.asText()))throw new Problem(502,"TOOL_NOT_ALLOWED","The model requested an unknown or duplicate tool.");
  }
  public static void validateReport(JsonNode value, Set<String> allowedRefs, Set<String> compatibleCodes, boolean missingEvidence) {
    exactFields(value,Set.of("diagnosisCode","summary","findings","missingEvidence","nextSteps"));
    if(!value.isObject() || !value.path("diagnosisCode").isTextual() || !DIAGNOSES.contains(value.path("diagnosisCode").asText()))invalid("MODEL_OUTPUT_INVALID","Invalid diagnosis code.");
    if(!compatibleCodes.contains(value.path("diagnosisCode").asText()))invalid("MODEL_CONTRADICTS_FACTS","The model's classification contradicts the captured event and ledger state.");
    if(!value.path("summary").isTextual() || value.path("summary").asText().isBlank() || value.path("summary").asText().length()>400)invalid("MODEL_OUTPUT_INVALID","Invalid report summary.");
    var findings=value.path("findings");
    if(!findings.isArray()||findings.isEmpty()||findings.size()>4)invalid("MODEL_OUTPUT_INVALID","Expected one to four cited findings.");
    for(var finding:findings) {
      exactFields(finding,Set.of("text","evidenceIds"));
      if(!finding.path("text").isTextual()||finding.path("text").asText().isBlank()||finding.path("text").asText().length()>240)invalid("MODEL_OUTPUT_INVALID","Invalid finding.");
      var refs=finding.path("evidenceIds");
      if(!refs.isArray()||refs.isEmpty()||refs.size()>4)invalid("UNCITED_FINDING","Every finding requires evidence references.");
      for(var ref:refs)if(!ref.isTextual()||!allowedRefs.contains(ref.asText()))invalid("UNKNOWN_CITATION","The model referenced evidence outside the captured incident.");
    }
    var steps=value.path("nextSteps");
    if(!steps.isArray()||steps.isEmpty()||steps.size()>3)invalid("MODEL_OUTPUT_INVALID","Expected bounded next-step identifiers.");
    for(var step:steps)if(!step.isTextual()||!NEXT_STEPS.contains(step.asText()))invalid("ACTION_NOT_ALLOWED","The model proposed an unsupported next step.");
    var missing=value.path("missingEvidence");
    if(!missing.isArray()||missing.size()>4)invalid("MODEL_OUTPUT_INVALID","Invalid missing-evidence list.");
    for(var item:missing)if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>140)invalid("MODEL_OUTPUT_INVALID","Invalid missing-evidence description.");
    if(missingEvidence&&missing.isEmpty())invalid("MISSING_ABSTENTION","An incomplete incident must name the missing evidence.");
  }
  private static void invalid(String code,String message) { throw new Problem(502,code,message); }
  private static void exactFields(JsonNode value,Set<String> fields) {
    if(!value.isObject())invalid("MODEL_OUTPUT_INVALID","Expected a JSON object.");
    var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
    if(!actual.equals(fields))invalid("MODEL_OUTPUT_INVALID","The model returned missing or unexpected fields.");
  }
}
