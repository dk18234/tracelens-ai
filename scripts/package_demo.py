#!/usr/bin/env python3
"""Package the same UI for GitHub Pages using measured, real-model recordings."""
import json
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[1]
source = root / "src/main/resources/static"
docs = root / "docs"
data = json.loads((docs / "demo-data.json").read_text())
assert data["mode"] == "recorded-real-model"
assert data["recordings"], "Generate actual recordings first using live_evaluation.py"
for item in data["recordings"]:
    result = item["job"].get("result")
    if result:
        assert result["mode"] == "live-local-model"
        assert result["usage"]["inputTokens"] > 0
for path in source.iterdir():
    if path.is_file():
        shutil.copyfile(path, docs / path.name)
html = (docs / "index.html").read_text().replace('data-runtime="live"', 'data-runtime="recorded"')
(docs / "index.html").write_text(html)
(docs / ".nojekyll").write_text("")
print(f"Packaged {len(data['recordings'])} actual AI sessions for static hosting.")
