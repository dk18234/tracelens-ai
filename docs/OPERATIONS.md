# Local operation

## Connect the two Compose applications

Use a dedicated bridge with an explicit alias; both stacks have a service named `app`, so relying on that shared name can be ambiguous.

Create the network once:

```bash
docker network create tracelens-bridge
```

In the **Webhook Recovery Lab** repository, start its stack and attach its application container:

```bash
docker compose up --build -d
docker network connect --alias webhook-lab tracelens-bridge "$(docker compose ps -q app)"
```

In the **TraceLens** repository, set `WEBHOOK_ADMIN_TOKEN` in `.env` to Webhook Lab's operator token, then:

```bash
docker compose -f compose.yaml -f compose.bridge.yaml up --build -d
```

The optional override provides `WEBHOOK_LAB_URL=http://webhook-lab:8080` and attaches TraceLens to the bridge. Capture a Webhook Lab event ID in the TraceLens UI. The backend performs only GET upstream. Reattach Webhook Lab if you recreate its container. To stop TraceLens with this override, use both `-f` arguments again.

For Java processes running directly on the same host, set `WEBHOOK_LAB_URL=http://localhost:8080` instead; no Docker bridge is needed.

## Investigation failure codes

| Code | Meaning and response |
| --- | --- |
| `MODEL_UNAVAILABLE` | Start Ollama and verify both configured models are installed. |
| `MODEL_OUTPUT_INVALID` | The model returned an empty, malformed, or structurally invalid response. Start a new investigation after inspecting configuration. |
| `TOOL_NOT_ALLOWED` | An unknown or duplicate model tool was rejected before execution. |
| `UNKNOWN_CITATION` | A model finding referenced a source outside this incident. The bounded repair failed. |
| `MODEL_CONTRADICTS_FACTS` | The classification failed captured event/ledger invariants. |
| `ADVICE_CONTRADICTS_POLICY` | The model proposed advice inapplicable to the current state. |
| `INVENTED_EVIDENCE_GAP` | The model claimed an evidence gap unsupported by the capture. |
| `EMBEDDING_INVALID` | The embedding response had an invalid count, dimension, numeric value, or zero vector. |
| `WORKER_INTERRUPTED` | The running job lease expired. It is not automatically retried. Submit a new request explicitly. |
| `REQUEST_KEY_CONFLICT` | The key was already used for another question or incident revision. |
| `WEBHOOK_NOT_CONFIGURED` | Configure the upstream URL and token on the server. |

A failed investigation has no replacement report. Existing accepted investigations remain in PostgreSQL. Queue processing is one active job per JVM; additional replicas coordinate claims in the database. This version has no per-user quotas or automatic history retention.

## Local test traffic

`scripts/webhook_bridge_smoke.py` is an explicit test utility for a **disposable local Webhook Lab**. It sends a new signed synthetic event and drives that lab's worker, then verifies TraceLens imports without changing its business state. The utility's writes are separate from TraceLens's read-only importer.

```bash
export WEBHOOK_BASE_URL=http://localhost:8080
export WEBHOOK_ADMIN_TOKEN=local-admin-token-change-before-hosting
export WEBHOOK_SECRET=local-webhook-signing-secret-change-me
export ADMIN_TOKEN=local-tracelens-token-change-before-hosting
python scripts/webhook_bridge_smoke.py
```

Use the actual local tokens and signing secret from your environments. Never point this synthetic-traffic utility at a production service.

## Reproduce the public demo

```bash
python scripts/live_evaluation.py --demo-output docs/demo-data.json
python scripts/package_demo.py
```

Review the resulting evidence before publishing. Only synthetic data belongs in the public repository. GitHub Pages serves `main:/docs`. The packaging script changes the shared UI's runtime marker to `recorded`; it does not synthesize model output. A model failure remains visible as a failed case. The source UI stays in live mode.

## Diagnosing model quality

Changing the model, corpus, prompt, runtime version, or retrieval settings invalidates assumptions about previous results. Re-run the evaluator. Classification and advice are constrained by backend invariants; the remaining natural-language explanation can still be wrong. Inspect the source records behind its citations and add held-out, paraphrased cases before making broader claims.
