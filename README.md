# TraceLens · AI Incident Investigator

An event says **PROCESSING**. Its ledger already contains the credit. Should an operator replay it?

TraceLens turns captured event state, attempts, ledger entries, logs, and retrieved runbooks into a cited explanation. A real local language model selects read tools and writes the report. Java captures the evidence, constrains and verifies the output, and independently decides recovery eligibility. No recovery action executes from this app.

**[Explore the recorded AI demo](https://dk18234.github.io/tracelens-ai/)** · **[Architecture](docs/ARCHITECTURE.md)** · **[Evaluation](docs/EVALUATION.md)** · **[Learn it step by step](docs/LEARNING_GUIDE.md)**

The public demo replays actual local-model sessions on synthetic incidents. It does not run live inference or accept new questions. The full local application below includes the Java service, PostgreSQL, real embedding and chat inference, editable questions, and a read-only connection to [Webhook Recovery Lab](https://github.com/dk18234/webhook-recovery-lab).

## What is implemented

- Evidence tools scoped to one captured incident: event, attempts, ledger, and logs. The trace distinguishes model-selected reads from the complete evidence set required by Java.
- Hybrid runbook retrieval: PostgreSQL English full-text search, actual `all-minilm` embeddings, exact cosine similarity, and reciprocal-rank fusion.
- Structured reports with source references, bounded advice, explicit missing evidence, and at most one repair. Three chat calls maximum per investigation.
- Deterministic recovery policy that distinguishes unavailable ledger evidence from a successful empty query and blocks replay of committed effects.
- Durable queued investigations, request-key idempotency, immutable revisions, leased claims, and explicit failure after interrupted model work.
- A complete investigation UI, evidence inspection, retrieval rankings, raw JSON/Markdown export, runbook browser, and evaluation view.
- Real PostgreSQL contract/integration tests, browser-domain checks, a separate real-model evaluator, and container/model CI smoke checks.

## Stack

| Layer | Implementation |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5.16, Spring JDBC, Flyway |
| Persistence | PostgreSQL 16; JSONB evidence, English full-text index, vector arrays |
| Chat inference | Ollama HTTP API; default `qwen2.5:3b` |
| Embeddings | Ollama `all-minilm`; vectors generated locally and persisted |
| Retrieval | Exact cosine in Java + reciprocal-rank fusion, k=60 |
| UI | Accessible HTML, CSS, JavaScript modules; shared live/recorded interface |
| Packaging | Docker Compose, Maven Wrapper, GitHub Actions, GitHub Pages |

This project uses direct Ollama HTTP integration. It does not use Spring AI, pgvector, an ANN index, or paid model APIs.

## Run the full application

Install Docker with Compose. The default model weights total about 2 GB, with additional space required for images, the database, and build dependencies. Allow roughly 8 GB of available memory; actual CPU latency depends on your machine. Model weights have their own licenses; the source license does not replace them.

```bash
git clone https://github.com/dk18234/tracelens-ai.git
cd tracelens-ai
cp .env.example .env
docker compose up --build -d
docker compose logs -f model-init
```

On the first start, `model-init` downloads both configured models. The app starts after those downloads complete. Open **http://localhost:8081** and use `ADMIN_TOKEN` from `.env` to connect. The example token is for local development only. The application binds to localhost; PostgreSQL and Ollama are internal services.

Choose an incident, ask a question, then inspect Findings, Evidence, Tool trace, and Retrieval. First inference includes model loading and embedding the runbooks. Later requests reuse persisted embeddings. An unavailable model produces an explicit failed investigation, never a canned AI answer.

```bash
docker compose logs -f app
docker compose stop                 # Preserve models, evidence, and history
docker compose up -d               # Resume
```

To change the model, edit `CHAT_MODEL` in `.env`, run `docker compose up -d --force-recreate model-init app`, and re-evaluate. Scores from one model do not automatically transfer to another.

### Run Java directly

Start PostgreSQL and [Ollama](https://ollama.com), then:

```bash
ollama pull qwen2.5:3b
ollama pull all-minilm
export DATABASE_URL=jdbc:postgresql://localhost:5432/tracelens
export DATABASE_USER=tracelens
export DATABASE_PASSWORD=tracelens_local
export ADMIN_TOKEN=local-tracelens-token-change-before-hosting
./mvnw spring-boot:run
```

Create the named database and user before starting. Flyway creates the tables; the app seeds eight synthetic incidents and twelve runbook chunks. Incident seeds do not overwrite existing revisions. Java 21 **JDK** is required for builds. Maven is downloaded by the wrapper.

## Investigate through the API

```bash
curl -s http://localhost:8081/api/incidents \
  -H "X-Admin-Token: $ADMIN_TOKEN"

curl -s http://localhost:8081/api/investigations \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"incidentId":"inc_ack_lost","question":"Why is this event still processing?","requestKey":"review_001"}'
```

Poll `/api/investigations/{returned-id}` until `COMPLETED`, `NEEDS_EVIDENCE`, or `FAILED`. Repeating an identical request key returns the same job; changing the question or revision with that key returns 409.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/config` | Model names and runtime capabilities |
| `GET /api/incidents` | Incident inbox |
| `GET /api/incidents/{id}` | Current captured incident |
| `POST /api/investigations` | Queue a question against a captured revision |
| `GET /api/investigations` | Recent durable job history |
| `GET /api/investigations/{id}` | Report, evidence, trace, usage, or failure |
| `GET /api/runbooks` | Inspect the authored corpus |
| `POST /api/retrieval` | Compare retrieval for `{"query":"..."}` |
| `POST /api/import/webhook` | Read upstream event using `{"eventId":"..."}` |
| `POST /api/worker/tick` | Claim and run one queued job manually |
| `GET /actuator/health` | Unauthenticated minimal health check |

All `/api/` endpoints require the operator token. The manual tick can block while inference runs; the UI uses queued investigation polling instead.

## Connect to project one

Run [Webhook Recovery Lab](https://github.com/dk18234/webhook-recovery-lab) on port 8080. Configure TraceLens with `WEBHOOK_LAB_URL` and that service's `WEBHOOK_ADMIN_TOKEN`. Use the capture form to enter an event ID. The adapter makes a bounded, read-only `GET /api/events/{id}` and saves a new revision.

For two Compose stacks, use the explicit network alias and included `compose.bridge.yaml` override described in [the bridge setup](docs/OPERATIONS.md#connect-the-two-compose-applications). For directly running Java processes, use `http://localhost:8080`. A container's `localhost` refers to itself.

TraceLens cannot replay an upstream event, change its fault mode, or update its ledger. A separate smoke utility can create synthetic test traffic against a disposable local Webhook Lab to verify the bridge.

## Verify and evaluate

```bash
./mvnw -B verify
node --check src/main/resources/static/app.js
node --test scripts/ui-domain.test.mjs

# Against the running full backend; actual LLM and embeddings, no test double:
python scripts/live_evaluation.py --demo-output docs/demo-data.json
python scripts/package_demo.py
```

The evaluator keeps expected labels outside model requests, records failures, measures token usage and wall-clock latency, and compares lexical/semantic/hybrid recall@3 on eight labeled queries. The raw checked-in results and limitations are in [docs/evidence](docs/evidence) and [the methodology](docs/EVALUATION.md). The schema and backend rules constrain accepted labels, so this is an evaluation of the guarded system, not an unrestricted model accuracy benchmark.

The Java integration suite uses real PostgreSQL and an explicit model HTTP test double to inject invalid citations, unsupported tools, empty responses, and bad embeddings. These deterministic checks are separate from actual-model evaluation. GitHub Actions additionally builds the Compose stack, downloads real weights, and runs a live-model smoke investigation.

## Scope and limitations

The evidence corpus and incidents are synthetic and small. No production incidents, user data, or business-impact numbers are claimed. Reference checks establish that a source ID exists and belongs to this incident; they do **not** prove every natural-language sentence follows from the source. Human review remains necessary.

The backend restricts applicable advice and classification codes using captured event and ledger invariants. This improves boundaries but cannot turn incomplete or incorrect source data into truth. The public recordings are deliberately labeled, and new questions require a running local backend. See [architecture](docs/ARCHITECTURE.md), [operations](docs/OPERATIONS.md), and [the learning guide](docs/LEARNING_GUIDE.md) for deeper inspection.

Built by **Dinesh Kumar Rama** as an applied AI portfolio project with AI-assisted implementation.
