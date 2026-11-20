# Evaluation methodology

The checked-in [live evaluation](evidence/live-evaluation.json) and [demo recordings](demo-data.json) contain actual HTTP responses from Java, PostgreSQL, and Ollama. The model and embedding names, timestamps, token counts, durations, labels, retrieval rankings, failures, and corpus hash are recorded rather than estimated. The public interface presents those saved sessions as recordings.

## What the checks measure

| Layer | Evidence | Meaning |
| --- | --- | --- |
| Policy and model contracts | Java unit tests | Captured invariants, reference scope, output shape, bounded advice, redaction, vector math |
| Backend integration | Real PostgreSQL + explicit model HTTP double | Queue/idempotency behavior, SQL retrieval wiring, invalid model outputs, authentication/body bounds, revisioned GET imports |
| UI domain | Node built-in test runner | Escaping untrusted evidence, combined inbox filters, export behavior |
| Actual model behavior | Ollama chat and embedding endpoints | Guarded diagnosis labels, accepted references, actual token usage and latency on synthetic cases |
| Retrieval comparison | Eight labeled queries over twelve chunks | Relevant document appears among the first three keyword, semantic, or fused results |
| Process interruption | Actual Java SIGKILL and restart | A queued job survives with the same ID and captured revision; no model call runs while the worker is disabled |
| Cross-project bridge | Two real Java applications with separate PostgreSQL clusters | Signed synthetic event commits once; read-only TraceLens imports increment revisions without changing upstream event/ledger |
| Container smoke | GitHub Actions Compose job | Built images, real downloaded models, actual HTTP model investigation |

The HTTP model double is never used to create public recordings or claim model quality. It exists to deterministically exercise failures that an actual model may not produce on demand. Application tests start PostgreSQL using the embedded binary distribution; they do not replace SQL behavior with an in-memory database.

## Fixed incident set

Eight synthetic cases cover a lost completion acknowledgement after a credit commit, transient dependency timeout, terminal mapping error, repeated duplicate deliveries, an unavailable ledger, an expired lease before commit, conflicting credit amounts, and hostile instructions embedded in logs. Event IDs are opaque hashes rather than diagnosis names. Expected labels stay in the evaluator and are not submitted as answers to the model.

Java independently constrains report classification and advice using project-specific event/ledger rules. The resulting label score measures the **guarded system**, not unrestricted LLM classification accuracy. Some cases permit abstention even when a more specific expected label exists; a strict label comparison still counts that as a mismatch. Failed investigations remain in the report and do not receive fabricated results.

## Retrieval set

Eight authored queries have one labeled relevant runbook each. Four corpus chunks provide general context or unrelated distractors. Lexical search uses PostgreSQL English full-text search, semantic ranking uses actual local embeddings and exact cosine similarity, and hybrid ranking uses reciprocal-rank fusion with k=60. `Recall@3` is the fraction of queries whose relevant document appears in the first three results.

These are small, hand-authored examples. The questions and runbooks were designed together, so the scores are optimistic and do not estimate performance on a held-out production distribution. More independent queries, paraphrases, larger distractor sets, and multiple reviewers are needed before broader claims.

## Iteration evidence

[baseline-evaluation.json](evidence/baseline-evaluation.json) preserves an early real run using the smaller `qwen2.5:1.5b` model before prompt/schema/advice improvements. It demonstrates why invalid classifications were rejected and why correct structured labels do not guarantee correct prose. The final run uses `qwen2.5:3b`, schema grounding in the prompt, explicit captured facts, applicable advice constraints, and invariant-constrained classification. This is an engineering iteration, not a controlled single-variable model comparison.

## What is deliberately not claimed

A source ID can exist while a model's sentence misstates its contents. Reference verification establishes existence and scope; it does **not** establish semantic entailment. Structured classification rules and advice constraints reduce specific failure modes, but human review of natural-language findings remains necessary. No “hallucination-free” claim is made.

Recorded latency is from CPU inference in the build environment, includes service/model/embedding overhead as encountered, and varies with caching and hardware. Reported token counts come from Ollama response counters. Temperature 0 and seed 42 do not guarantee identical output across versions or machines. This is a single fixed-set run, not a statistical benchmark or a production SLO.

## Reproduce

```bash
./mvnw -B verify
node --test scripts/ui-domain.test.mjs
# Start the full service with both local models before running:
python scripts/live_evaluation.py --demo-output docs/demo-data.json
python scripts/package_demo.py
```

The evaluator exits nonzero on a wrong/missing diagnosis or invalid references and still writes the observed results. The CI smoke selects one incident to keep container validation bounded; the checked-in local evaluation covers the full fixed set. Review raw results and compare cited prose with evidence rather than relying only on a pass count.
