import os
os.environ["MPLCONFIGDIR"] = "/tmp/mpl_cache"

import json
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

FIGURES_DIR = Path("eval/figures")
FIGURES_DIR.mkdir(parents=True, exist_ok=True)
RESULTS_FILE = Path("eval/rag_vs_norag_results.json")

# Professional Poster Palette
UMKC_BLUE = "#004b87"
GRAY = "#708090"  # Clean slate gray
UMKC_DARK = "#0a2540"
BG = "#ffffff"

with open(RESULTS_FILE) as f:
    results = json.load(f)

N = len(results)
rag_recalls = [r["rag"]["recall"] * 100 for r in results]
norag_recalls = [r["norag"]["recall"] * 100 for r in results]

rag_cite_rate = sum(1 for r in results if r["rag"]["has_citations"]) / N * 100
norag_cite_rate = sum(1 for r in results if r["norag"]["has_citations"]) / N * 100

rag_pol_rate = sum(1 for r in results if r["rag"]["has_specific_policy"]) / N * 100
norag_pol_rate = sum(1 for r in results if r["norag"]["has_specific_policy"]) / N * 100

avg_rag_recall = np.mean(rag_recalls)
avg_norag_recall = np.mean(norag_recalls)

# Standard Errors (SE)
sem_rag_recall = np.std(rag_recalls, ddof=1) / np.sqrt(N)
sem_norag_recall = np.std(norag_recalls, ddof=1) / np.sqrt(N)

# Binary proportion standard errors: sqrt(p*(1-p)/N)
sem_rag_cite = np.sqrt((rag_cite_rate/100) * (1 - rag_cite_rate/100) / N) * 100
sem_norag_cite = 0.0

sem_rag_pol = np.sqrt((rag_pol_rate/100) * (1 - rag_pol_rate/100) / N) * 100
sem_norag_pol = np.sqrt((norag_pol_rate/100) * (1 - norag_pol_rate/100) / N) * 100

rag_errs = [sem_rag_recall, sem_rag_cite, sem_rag_pol]
norag_errs = [sem_norag_recall, sem_norag_cite, sem_norag_pol]

metrics = ["Policy Fact Recall", "Citation Grounding Rate", "University Policy Specificity"]
rag_vals = [avg_rag_recall, rag_cite_rate, rag_pol_rate]
norag_vals = [avg_norag_recall, norag_cite_rate, norag_pol_rate]

# Poster-optimized dimensions and styling
fig, ax = plt.subplots(figsize=(10.5, 6.2))
x = np.arange(len(metrics))
w = 0.34

# Bars with subtle error bars
b1 = ax.bar(x - w/2, rag_vals, w, yerr=rag_errs, capsize=4, error_kw=dict(elinewidth=1.3, ecolor="#1a365d", capthick=1.3),
            label="RAG (+ Output Guard)", color=UMKC_BLUE, zorder=3)
b2 = ax.bar(x + w/2, norag_vals, w, yerr=norag_errs, capsize=4, error_kw=dict(elinewidth=1.3, ecolor="#475569", capthick=1.3),
            label="No-RAG Baseline (+ Output Guard)", color=GRAY, zorder=3)

# Clear academic title (no brackets, no promotional phrasing)
ax.set_title("Performance Comparison: RAG vs. No-RAG Baseline (N=50)",
             fontsize=16, fontweight="bold", color=UMKC_DARK, pad=18)

ax.set_ylabel("Score (%)", fontsize=13.5, fontweight="bold", color=UMKC_DARK)
ax.set_xticks(x)
ax.set_xticklabels(metrics, fontsize=13, fontweight="bold", color="#1e293b")

# Strict 0-100% y-axis cap
ax.set_ylim(0, 105)
ax.set_yticks([0, 20, 40, 60, 80, 100])
ax.set_yticklabels(["0%", "20%", "40%", "60%", "80%", "100%"], fontsize=12)
ax.yaxis.grid(True, linestyle="--", alpha=0.5, zorder=0)

# Uncluttered legend placed cleanly
ax.legend(frameon=True, facecolor="white", edgecolor="#cbd5e1", loc="upper right", fontsize=12)

# Large, readable bar labels placed just above error bars
for bar, val, err in zip(b1, rag_vals, rag_errs):
    ax.text(bar.get_x() + bar.get_width()/2, val + err + 2.0, f"{val:.1f}%",
            ha="center", va="bottom", fontsize=12.5, fontweight="bold", color=UMKC_BLUE)

for bar, val, err in zip(b2, norag_vals, norag_errs):
    ax.text(bar.get_x() + bar.get_width()/2, val + err + 2.0, f"{val:.1f}%",
            ha="center", va="bottom", fontsize=12.5, fontweight="bold", color="#334155")

# Spines styling
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
ax.spines["left"].set_color("#94a3b8")
ax.spines["bottom"].set_color("#94a3b8")

fig.tight_layout()
p1 = FIGURES_DIR / "05_rag_vs_norag_accuracy.png"
fig.savefig(p1, dpi=200, bbox_inches="tight")
plt.close(fig)
print(f"✅ Saved pristine Poster Chart 1 → {p1}")
