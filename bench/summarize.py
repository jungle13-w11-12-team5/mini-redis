"""bench/results/raw/*.json → bench/results/summary.csv + summary.md (요약 표)"""
import csv
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent / "results"
rows = []
for p in sorted((ROOT / "raw").glob("w*-cache-*.json")):
    d = json.loads(p.read_text(encoding="utf-8"))
    c = d["cache"]
    reads = sum(c.values())
    lookups = c["hit"] + c["miss"]
    s = d.get("server", {})
    rows.append({
        "run": d["run"],
        "write_pct": d["writePct"],
        "cache": "on" if "cache-on" in d["run"] else "off",
        "rps": round(d["rps"]),
        "p50_ms": round(d["all"]["p50"], 2),
        "p99_ms": round(d["all"]["p99"], 2),
        "id_p50_ms": round(d["id"]["p50"] or 0, 2),
        "id_p99_ms": round(d["id"]["p99"] or 0, 2),
        "name_p50_ms": round(d["name"]["p50"] or 0, 2),
        "name_p99_ms": round(d["name"]["p99"] or 0, 2),
        "hit_ratio": "" if lookups == 0 else f"{c['hit'] / lookups * 100:.1f}%",
        "hit": c["hit"], "miss": c["miss"], "bypass": c["bypass"], "skip": c["skip"], "off": c["off"],
        "writes": d["write"]["n"] or 0,
        "errors": d["errors"],
        "dbsize": s.get("stats", {}).get("dbsize"),
        "users_ver": s.get("stats", {}).get("ver"),
        "tbl_before_kb": round(s.get("tblBefore", 0) / 1024),
        "tbl_after_kb": round(s.get("tblAfter", 0) / 1024),
    })

if not rows:
    sys.exit("결과 없음: bench/results/raw/")

rows.sort(key=lambda r: (r["write_pct"], r["cache"] == "off"))
with open(ROOT / "summary.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    w.writeheader()
    w.writerows(rows)

cols = [("쓰기", "write_pct", "%"), ("캐시", "cache", ""), ("처리량 req/s", "rps", ""), ("p50 ms", "p50_ms", ""),
        ("p99 ms", "p99_ms", ""), ("id p50", "id_p50_ms", ""), ("검색 p50", "name_p50_ms", ""), ("검색 p99", "name_p99_ms", ""),
        ("hit 비율", "hit_ratio", ""), ("DBSIZE", "dbsize", ""), ("users:ver", "users_ver", ""),
        (".tbl KB 전→후", None, ""), ("오류", "errors", "")]
lines = ["| " + " | ".join(c[0] for c in cols) + " |", "|" + "---|" * len(cols)]
for r in rows:
    cells = []
    for _, key, suffix in cols:
        cells.append(f"{r['tbl_before_kb']}→{r['tbl_after_kb']}" if key is None else f"{r[key]}{suffix}")
    lines.append("| " + " | ".join(cells) + " |")
(ROOT / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))
