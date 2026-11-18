"""bench/results/raw/*.json → bench/results/summary.csv(회차별) + summary.md(조건별 중앙값 · 처리량 범위)"""
import csv
import json
import pathlib
import re
import statistics
import sys

ROOT = pathlib.Path(__file__).resolve().parent / "results"


def row_of(d):
    c = d["cache"]
    lookups = c["hit"] + c["miss"]
    s = d.get("server", {})
    m = re.match(r"(w\d+-cache-(on|off))(?:-r(\d+))?$", d["run"])
    return {
        "run": d["run"],
        "cond": m.group(1),
        "repeat": int(m.group(3) or 1),
        "write_pct": d["writePct"],
        "cache": m.group(2),
        "rps": round(d["rps"]),
        "p50_ms": round(d["all"]["p50"], 2),
        "p99_ms": round(d["all"]["p99"], 2),
        "id_p50_ms": round(d["id"]["p50"] or 0, 2),
        "id_p99_ms": round(d["id"]["p99"] or 0, 2),
        "name_p50_ms": round(d["name"]["p50"] or 0, 2),
        "name_p99_ms": round(d["name"]["p99"] or 0, 2),
        "hit_ratio": None if lookups == 0 else round(c["hit"] / lookups * 100, 1),
        "hit": c["hit"], "miss": c["miss"], "bypass": c["bypass"], "skip": c["skip"], "off": c["off"],
        "writes": d["write"]["n"] or 0,
        "errors": d["errors"],
        "dbsize": s.get("stats", {}).get("dbsize"),
        "users_ver": int(s.get("stats", {}).get("ver") or 0),
        "tbl_before_kb": round(s.get("tblBefore", 0) / 1024),
        "tbl_after_kb": round(s.get("tblAfter", 0) / 1024),
    }


rows = [row_of(json.loads(p.read_text(encoding="utf-8"))) for p in sorted((ROOT / "raw").glob("w*-cache-*.json"))]
if not rows:
    sys.exit("결과 없음: bench/results/raw/")
rows.sort(key=lambda r: (r["write_pct"], r["cache"] == "off", r["repeat"]))

with open(ROOT / "summary.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    w.writeheader()
    w.writerows(rows)

conds = {}
for r in rows:
    conds.setdefault(r["cond"], []).append(r)


def med(rs, key, digits=1):
    vals = [r[key] for r in rs if r[key] is not None]
    if not vals:
        return "-"
    v = statistics.median(vals)
    return f"{v:,.0f}" if digits == 0 else f"{v:,.{digits}f}"


head = ["쓰기", "캐시", "n", "처리량 req/s (최소~최대)", "p50 ms", "p99 ms", "id p50", "검색 p50", "검색 p99",
        "hit 비율", "DBSIZE", "users:ver", ".tbl KB 후", "오류"]
lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
for cond, rs in conds.items():
    rps = [r["rps"] for r in rs]
    hit = med(rs, "hit_ratio")
    lines.append("| " + " | ".join([
        f"{rs[0]['write_pct']}%", "켬" if rs[0]["cache"] == "on" else "끔", str(len(rs)),
        f"{med(rs, 'rps', 0)} ({min(rps):,}~{max(rps):,})",
        med(rs, "p50_ms"), med(rs, "p99_ms"), med(rs, "id_p50_ms"), med(rs, "name_p50_ms"), med(rs, "name_p99_ms"),
        "-" if hit == "-" else hit + "%", med(rs, "dbsize", 0), med(rs, "users_ver", 0), med(rs, "tbl_after_kb", 0),
        str(sum(r["errors"] for r in rs)),
    ]) + " |")
md = "\n".join(lines) + "\n\n값은 조건별 중앙값. 회차별 원자료: summary.csv · raw/\n"
(ROOT / "summary.md").write_text(md, encoding="utf-8")
print(md)
