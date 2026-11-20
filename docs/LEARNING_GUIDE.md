# Learn TraceLens by taking it apart

The app is implemented so you can inspect a working system before building pieces yourself. Spend your first session explaining one incident and one API request. Do not start by memorizing AI vocabulary.

## 1. Understand the problem first

Open the demo's **The credit landed. The acknowledgement didn't.** case. Click E1 and E3 in a finding. E1 shows the event state; E3 shows the committed business effect. Explain aloud why retrying the credit grant could be wrong even though the event is still processing.

Read `RecoveryPolicy.java`. Predict the result when the ledger is unavailable, empty, matching, or conflicting. Then read `PolicyAndContractTest.java` and run it:

```bash
./mvnw -Dtest=PolicyAndContractTest test
```

Checkpoint: explain the difference between **unknown**, **known empty**, and **known committed** without referring to the UI.

## 2. Follow a question through the backend

Read `ApiController.java`, then `Investigations.java`, then `V1__evidence_workspace.sql`. Submit a question with the same request key twice. Inspect the returned ID. Change the question but keep the key. Observe 409 rather than another model call.

Ask why a long network call should not hold a database transaction open. Find the lease token in the claim and completion SQL. Explain what an interrupted worker leaves behind and why the app does not silently retry model calls.

Exercise: submit a queued job with `WORKER_ENABLED=false`, restart the process, then call `/api/worker/tick`. Verify the job survives. A running job whose lease expires must fail explicitly; it must not become completed without an answer.

## 3. Understand the AI boundary

Read `OllamaClient.java`, `ModelContracts.java`, and `InvestigationPipeline.java`. Find the first model call: it creates a JSON read plan. Find the second: it explains the captured incident. Find the optional third: it repairs a rejected answer.

The `format` parameter constrains generation. Including the schema in the prompt also tells the model what each output field should contain. Java still validates the result. The model's JSON is untrusted input, just like an HTTP request.

Checkpoint: identify what the model chooses, what Java computes, and what no component is authorized to execute. Explain why schema-constrained application planning is different from native model tool calls.

## 4. Change retrieval and measure the effect

Read `HybridRetriever.java` and the runbook JSON. Call `/api/retrieval` with an exact operational phrase, then a paraphrase. Inspect lexical rank, semantic rank, and fused score.

Embeddings are numeric vectors from a trained model, not a hand-written keyword match. Exact cosine scoring is appropriate for twelve documents. A thousand is the deliberate upper bound. Explain when you would move scoring into an indexed vector store and why you would benchmark that change.

Exercise: add a relevant runbook, edit another, restart the app, and verify the changed content hash is re-embedded. Add a distractor containing the exact keywords but wrong operational context. Re-run retrieval evaluation; do not assume more documents always help.

## 5. Break the contracts on purpose

Read `BackendIntegrationTest.java`. Its model service is an explicit HTTP test double for deterministic failure injection; it does not measure actual model intelligence. PostgreSQL in these tests is real.

Run:

```bash
./mvnw verify
node --test scripts/ui-domain.test.mjs
python scripts/live_evaluation.py
```

Try unknown tool names, invented source IDs, a missing ledger, misleading log instructions, invalid embedding dimensions, and an unavailable model. The live evaluator is separate from the fake model tests and writes actual responses and measured usage.

Checkpoint: explain why a valid citation ID does not prove the sentence is supported. Find an example where a model might cite the right record while describing it incorrectly. Propose an additional structured fact check or human review boundary.

## 6. Connect the two portfolio projects

Run Webhook Recovery Lab locally. Configure its URL and operator token on the TraceLens server. Capture a real event from TraceLens, then investigate the captured revision. Inspect Webhook Lab before and after: TraceLens must not modify the upstream ledger or event.

The bridge smoke script creates a synthetic event in a disposable local Webhook Lab, explicitly drives its worker, captures it twice, and verifies revisions and unchanged upstream business state. It is a test utility; the TraceLens adapter itself only performs GET.

## Interview walkthrough

“TraceLens is an AI-assisted backend incident investigator. It captures an immutable incident revision, uses a local model to plan evidence reads, combines keyword and embedding retrieval over runbooks, and generates a cited explanation. Java validates the report and independently computes recovery eligibility. I deliberately separate reference integrity from semantic correctness, and I evaluate real model behavior separately from deterministic contract tests.”

Then demonstrate one incident, one failure case, one retrieval comparison, and one code path. Be clear about using AI assistance to build the project. Claim personal understanding and independent changes as you acquire them; do not claim production users or operational impact.

## Resume wording

Once you can explain and modify the implementation:

- Built a Java 21/Spring Boot incident investigator integrating local LLM inference, hybrid runbook retrieval, and evidence-linked reports with PostgreSQL-backed investigation history.
- Implemented request deduplication, immutable incident revisions, leased job processing, bounded read tools, and independent recovery rules; evaluated model behavior on synthetic failure cases.

Use the measured evaluation file for exact numbers and name the small synthetic dataset if you include results. Avoid “eliminated hallucinations,” “production-grade autonomous agent,” or invented business savings.
