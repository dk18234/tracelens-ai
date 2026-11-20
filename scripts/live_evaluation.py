#!/usr/bin/env python3
"""Run actual HTTP investigations. No stub, canned answer, or fallback is used.

Expected labels live only in this evaluator and are never submitted to the model.
The output intentionally includes failures as well as successful diagnoses.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
QUERIES = [
    ("The credit committed but the completion acknowledgement was lost", "rb_ack"),
    ("HTTP 503 retryable dependency timeout exponential backoff", "rb_timeout"),
    ("Terminal invalid account mapping dead letter correction", "rb_poison"),
    ("Sixty duplicate deliveries one credit ledger entry", "rb_duplicates"),
    ("A missing ledger query is unknown, not an empty result", "rb_missing"),
    ("Expired worker lease before any business commit", "rb_stale"),
    ("Event and ledger credit amounts disagree", "rb_conflict"),
    ("User-controlled logs contain instructions to ignore policies", "rb_injection"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.getenv("LAB_URL", "http://localhost:8081"))
    ap.add_argument("--output", type=Path, default=ROOT / "docs/evidence/live-evaluation.json")
    ap.add_argument("--demo-output", type=Path)
    ap.add_argument("--cases", help="Comma-separated incident IDs; default is all eight")
    args = ap.parse_args()
    token = os.getenv("ADMIN_TOKEN", "local-tracelens-token-change-before-hosting")

    def api(path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(args.base_url.rstrip("/") + path, data=data,
            headers={"X-Admin-Token": token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=180) as response:
            return json.load(response)

    config = api("/api/config")
    fixtures = json.loads((ROOT / "src/main/resources/fixtures/incidents.json").read_text())
    if args.cases:
        selected = set(args.cases.split(","))
        fixtures = [f for f in fixtures if f["id"] in selected]
        if len(fixtures) != len(selected):
            raise SystemExit("Unknown case ID")
    cases, recordings = [], []
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    for case in fixtures:
        incident = api("/api/incidents/" + case["id"])
        receipt = api("/api/investigations", {"incidentId": case["id"],
            "question": case["question"], "requestKey": "eval_" + uuid.uuid4().hex})
        deadline = time.monotonic() + 620
        while True:
            job = api("/api/investigations/" + receipt["id"])
            if job["status"] in ("COMPLETED", "NEEDS_EVIDENCE", "FAILED"):
                break
            if time.monotonic() > deadline:
                raise TimeoutError("Investigation did not reach a terminal state")
            time.sleep(0.7)
        result = job.get("result") or {}
        report = result.get("report") or {}
        policy = result.get("policy") or {}
        ids = {e["id"] for e in result.get("evidence", [])}
        references_valid = bool(report) and all(
            f.get("evidenceIds") and set(f["evidenceIds"]) <= ids
            for f in report.get("findings", []))
        entry = {"incidentId": case["id"], "expectedDiagnosis": case["expectedDiagnosis"],
            "actualDiagnosis": report.get("diagnosisCode"), "status": job["status"],
            "correct": report.get("diagnosisCode") == case["expectedDiagnosis"],
            "referencesValid": references_valid, "replayEligible": policy.get("replayEligible"),
            "errorCode": job.get("error_code"), "usage": result.get("usage")}
        cases.append(entry)
        recordings.append({"incident": incident, "question": case["question"], "job": job})
        print(json.dumps(entry), flush=True)
    retrieval = []
    for query, target in QUERIES:
        found = api("/api/retrieval", {"query": query})
        hit_ids = [h["id"] for h in found["hits"]]
        retrieval.append({"query": query, "relevantDocument": target,
            "lexicalTop3": found["lexical"][:3], "semanticTop3": found["semantic"][:3],
            "hybridTop3": hit_ids, "lexicalHit": target in found["lexical"][:3],
            "semanticHit": target in found["semantic"][:3], "hybridHit": target in hit_ids})
    corpus = api("/api/runbooks")
    evidence = {"schemaVersion": 1, "startedAt": started,
        "completedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "method": "Real Java HTTP service + PostgreSQL + Ollama; greedy seed 42; synthetic fixed cases; single run, no statistical confidence claim.",
        "models": {"chat": config["chatModel"], "embedding": config["embeddingModel"]},
        "limits": {"maxChatCalls": 3, "readOnlyTools": True,
            "semanticEntailmentProven": False, "expectedLabelsSentToModel": False},
        "corpusSha256": hashlib.sha256(json.dumps(corpus, sort_keys=True).encode()).hexdigest(),
        "summary": {"diagnosisCorrect": sum(c["correct"] for c in cases), "cases": len(cases),
            "acceptedReferencesValid": sum(c["referencesValid"] for c in cases),
            "lexicalRecallAt3": sum(r["lexicalHit"] for r in retrieval) / len(retrieval),
            "semanticRecallAt3": sum(r["semanticHit"] for r in retrieval) / len(retrieval),
            "hybridRecallAt3": sum(r["hybridHit"] for r in retrieval) / len(retrieval)},
        "cases": cases, "retrieval": retrieval}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    if args.demo_output:
        args.demo_output.parent.mkdir(parents=True, exist_ok=True)
        args.demo_output.write_text(json.dumps({"mode": "recorded-real-model",
            "provenance": evidence, "recordings": recordings, "runbooks": corpus}, indent=2) + "\n")
    print(json.dumps(evidence["summary"]), flush=True)
    return 0 if all(c["correct"] and c["referencesValid"] for c in cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())
