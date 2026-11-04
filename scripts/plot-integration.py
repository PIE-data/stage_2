#!/usr/bin/env python3
"""Figures of the integration benchmarks (#8) from aggregate.csv (mean and sd over the runs).

    python3 scripts/plot-integration.py results/integration/<session>/aggregate.csv docs/img/integration

Needs matplotlib (sudo apt install -y python3-matplotlib). Every figure has one y-axis; error bars
are one standard deviation over the repeated runs. The numbers are also tabulated in
docs/INTEGRATION_RESULTS.md, so no value is shown by colour alone.
"""
import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"   # categorical slots 1-3, fixed order
INK, MUTED, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"


def load(path):
    m = defaultdict(dict)
    for r in csv.DictReader(open(path, encoding="utf-8")):
        if r["mean"]:
            m[r["label"]][r["metric"]] = (float(r["mean"]), float(r["sd"] or 0))
    return m


def axes(title, ylabel, xlabel):
    fig, ax = plt.subplots(figsize=(6.4, 3.6), dpi=150)
    fig.patch.set_facecolor(SURFACE)
    ax.set_facecolor(SURFACE)
    ax.set_title(title, loc="left", fontsize=11, color=INK)
    ax.set_ylabel(ylabel, color=MUTED, fontsize=9)
    ax.set_xlabel(xlabel, color=MUTED, fontsize=9)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(GRID)
    ax.tick_params(colors=MUTED, labelsize=8)
    return fig, ax


def line(ax, xs, means, sds, color, label, marker="o"):
    ax.errorbar(xs, means, yerr=sds, color=color, linewidth=2, marker=marker, markersize=6,
                capsize=3, elinewidth=1, label=label)


def bars(ax, names, means, sds, color):
    pos = range(len(names))
    ax.bar(pos, means, yerr=sds, color=color, width=0.6, capsize=3, error_kw={"elinewidth": 1})
    ax.set_xticks(list(pos), names)
    for p, v in zip(pos, means):
        ax.annotate(f"{v:.1f}", (p, v), textcoords="offset points", xytext=(8, 3), fontsize=8, color=INK)


def save(fig, out, name):
    fig.tight_layout()
    fig.savefig(out / name, facecolor=SURFACE)
    plt.close(fig)
    print("wrote", out / name)


def main():
    m = load(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    v = lambda label, metric: m[label][metric]  # noqa: E731

    # S1: throughput and p95 vs client concurrency (two figures: one axis each)
    cs = [1, 2, 4, 8, 16, 32]
    fig, ax = axes("S1 Search throughput vs client concurrency (1 replica)", "queries / s", "concurrent clients")
    line(ax, cs, [v(f"S1-c{c}", "throughput_qps")[0] for c in cs], [v(f"S1-c{c}", "throughput_qps")[1] for c in cs],
         BLUE, "throughput")
    ax.set_xscale("log", base=2)
    ax.set_xticks(cs, [str(c) for c in cs])
    save(fig, out, "s1-throughput.png")

    fig, ax = axes("S1 Search latency vs client concurrency (1 replica)", "latency (ms)", "concurrent clients")
    line(ax, cs, [v(f"S1-c{c}", "latency_ms.mean")[0] for c in cs], [v(f"S1-c{c}", "latency_ms.mean")[1] for c in cs],
         BLUE, "mean")
    line(ax, cs, [v(f"S1-c{c}", "latency_ms.p95")[0] for c in cs], [v(f"S1-c{c}", "latency_ms.p95")[1] for c in cs],
         ORANGE, "p95", marker="s")
    ax.set_xscale("log", base=2)
    ax.set_xticks(cs, [str(c) for c in cs])
    ax.legend(frameon=False, fontsize=8)
    save(fig, out, "s1-latency.png")

    # S2: replicas behind the gateway
    rs = [1, 2, 3]
    fig, ax = axes("S2 Search replicas behind the gateway", "queries / s", "Search replicas")
    for c, color, mk in ((16, BLUE, "o"), (32, ORANGE, "s")):
        line(ax, rs, [v(f"S2-r{r}-c{c}", "throughput_qps")[0] for r in rs],
             [v(f"S2-r{r}-c{c}", "throughput_qps")[1] for r in rs], color, f"{c} clients", marker=mk)
    ax.axhline(v("S1-c16", "throughput_qps")[0], color=MUTED, linestyle="--", linewidth=1)
    ax.annotate("1 replica, no gateway, 16 clients (S1)", (2, v("S1-c16", "throughput_qps")[0]),
                textcoords="offset points", xytext=(0, -12), fontsize=8, color=MUTED, ha="center")
    ax.set_xticks(rs)
    ax.set_ylim(0, v("S1-c16", "throughput_qps")[0] * 1.12)
    ax.legend(frameon=False, fontsize=8, loc="lower left")
    save(fig, out, "s2-replicas.png")

    # I1-I3: indexing throughput, one factor at a time
    for exp, labels, names, title, xlabel, name in (
            ("I1", ["I1-b1", "I1-b5", "I1-b20", "I1-b50"], ["1", "5", "20", "50"],
             "I1 Indexing throughput vs batch size", "messages per transaction", "i1-batch.png"),
            ("I2", ["I2-t1", "I2-t2", "I2-t4"], ["1", "2", "4"],
             "I2 Indexing throughput vs consumer sessions", "consumer sessions per replica", "i2-threads.png"),
            ("I3", ["I3-r1", "I3-r2", "I3-r3"], ["1", "2", "3"],
             "I3 Indexing throughput vs replicas (one SQLite file)", "Indexing replicas", "i3-replicas.png")):
        fig, ax = axes(title, "books / s", xlabel)
        bars(ax, names, [v(lbl, "throughput_books_s")[0] for lbl in labels],
             [v(lbl, "throughput_books_s")[1] for lbl in labels], BLUE)
        save(fig, out, name)

    # I4: per-book latency below saturation
    rates = [2, 5, 10]
    fig, ax = axes("I4 Per-book indexing latency below saturation", "latency (ms)", "books sent per second")
    for metric, color, mk, lbl in (("latency_ms.p50", BLUE, "o", "median"), ("latency_ms.p95", ORANGE, "s", "p95")):
        line(ax, rates, [v(f"I4-rate{r}", metric)[0] for r in rates], [v(f"I4-rate{r}", metric)[1] for r in rates],
             color, lbl, marker=mk)
    ax.set_xticks(rates)
    ax.set_ylim(bottom=0)
    ax.legend(frameon=False, fontsize=8)
    save(fig, out, "i4-latency.png")

    # P1: ingestion vs end-to-end
    ps = [1, 2, 4]
    fig, ax = axes("P1 Pipeline throughput vs ingestion replicas", "books / s", "Ingestion replicas")
    line(ax, ps, [v(f"P1-i{p}", "ingestion_throughput_books_s")[0] for p in ps],
         [v(f"P1-i{p}", "ingestion_throughput_books_s")[1] for p in ps], BLUE, "ingestion (stored in datalake)")
    line(ax, ps, [v(f"P1-i{p}", "throughput_books_s")[0] for p in ps],
         [v(f"P1-i{p}", "throughput_books_s")[1] for p in ps], ORANGE, "end to end (searchable)", marker="s")
    ax.set_xticks(ps)
    ax.set_ylim(bottom=0)
    ax.legend(frameon=False, fontsize=8, loc="lower left")
    save(fig, out, "p1-pipeline.png")

    # X1: search idle vs during indexing (two figures: throughput, p95)
    for metric, ylabel, title, name in (
            ("throughput_qps", "queries / s", "X1 Search throughput, idle vs during indexing (16 clients)", "x1-throughput.png"),
            ("latency_ms.p95", "p95 latency (ms)", "X1 Search p95 latency, idle vs during indexing (16 clients)", "x1-p95.png")):
        fig, ax = axes(title, ylabel, "")
        bars(ax, ["idle", "during re-index"], [v("X1-idle-c16", metric)[0], v("X1-indexing-c16", metric)[0]],
             [v("X1-idle-c16", metric)[1], v("X1-indexing-c16", metric)[1]], BLUE)
        save(fig, out, name)


if __name__ == "__main__":
    main()
