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
ARTIFACTS_DIR = Path("/Users/katreenadinh/.gemini/antigravity/brain/67b83cd1-c456-4497-9b2e-891709b1e77e")
RESULTS_FILE = Path("eval/rag_vs_norag_results.json")

# Unified Academic Palette
UMKC_BLUE = "#004b87"
UMKC_DARK = "#0a2540"
GRAY = "#708090"
GREEN = "#27ae60"
RED = "#c0392b"
GOLD = "#b8860b"

DPI = 300

plt.rcParams.update({
    "font.family": "sans-serif",
    "figure.facecolor": "#ffffff",
    "axes.facecolor": "#ffffff",
    "axes.spines.top": False,
    "axes.spines.right": False,
})

with open(RESULTS_FILE) as f:
    results = json.load(f)

N = len(results)

# =============================================================
# FIGURE 1 (05): Overall Performance Comparison (300 DPI)
# =============================================================
rag_recalls = [r["rag"]["recall"] * 100 for r in results]
norag_recalls = [r["norag"]["recall"] * 100 for r in results]

rag_cite_rate = sum(1 for r in results if r["rag"]["has_citations"]) / N * 100
norag_cite_rate = sum(1 for r in results if r["norag"]["has_citations"]) / N * 100

rag_pol_rate = sum(1 for r in results if r["rag"]["has_specific_policy"]) / N * 100
norag_pol_rate = sum(1 for r in results if r["norag"]["has_specific_policy"]) / N * 100

avg_rag_recall = np.mean(rag_recalls)
avg_norag_recall = np.mean(norag_recalls)

sem_rag_recall = np.std(rag_recalls, ddof=1) / np.sqrt(N)
sem_norag_recall = np.std(norag_recalls, ddof=1) / np.sqrt(N)
sem_rag_cite = np.sqrt((rag_cite_rate/100) * (1 - rag_cite_rate/100) / N) * 100
sem_norag_cite = 0.0
sem_rag_pol = np.sqrt((rag_pol_rate/100) * (1 - rag_pol_rate/100) / N) * 100
sem_norag_pol = np.sqrt((norag_pol_rate/100) * (1 - norag_pol_rate/100) / N) * 100

rag_errs = [sem_rag_recall, sem_rag_cite, sem_rag_pol]
norag_errs = [sem_norag_recall, sem_norag_cite, sem_norag_pol]

metrics = ["Policy Fact Recall", "Citation Grounding Rate", "University Policy Specificity"]
rag_vals = [avg_rag_recall, rag_cite_rate, rag_pol_rate]
norag_vals = [avg_norag_recall, norag_cite_rate, norag_pol_rate]

fig1, ax1 = plt.subplots(figsize=(10.5, 6.2))
x = np.arange(len(metrics))
w = 0.34

b1 = ax1.bar(x - w/2, rag_vals, w, yerr=rag_errs, capsize=4,
             error_kw=dict(elinewidth=1.3, ecolor="#1a365d", capthick=1.3),
             label="RAG (+ Output Guard)", color=UMKC_BLUE, zorder=3)
b2 = ax1.bar(x + w/2, norag_vals, w, yerr=norag_errs, capsize=4,
             error_kw=dict(elinewidth=1.3, ecolor="#475569", capthick=1.3),
             label="No-RAG Baseline (+ Output Guard)", color=GRAY, zorder=3)

ax1.set_title("Performance Comparison: RAG vs. No-RAG Baseline (N=50)",
              fontsize=16, fontweight="bold", color=UMKC_DARK, pad=18)
ax1.set_ylabel("Score (%)", fontsize=13.5, fontweight="bold", color=UMKC_DARK)
ax1.set_xticks(x)
ax1.set_xticklabels(metrics, fontsize=13, fontweight="bold", color="#1e293b")
ax1.set_ylim(0, 105)
ax1.set_yticks([0, 20, 40, 60, 80, 100])
ax1.set_yticklabels(["0%", "20%", "40%", "60%", "80%", "100%"], fontsize=12)
ax1.yaxis.grid(True, linestyle="--", alpha=0.5, zorder=0)
ax1.legend(frameon=True, facecolor="white", edgecolor="#cbd5e1", loc="upper right", fontsize=12)

for bar, val, err in zip(b1, rag_vals, rag_errs):
    ax1.text(bar.get_x() + bar.get_width()/2, val + err + 2.0, f"{val:.1f}%",
             ha="center", va="bottom", fontsize=12.5, fontweight="bold", color=UMKC_BLUE)

for bar, val, err in zip(b2, norag_vals, norag_errs):
    ax1.text(bar.get_x() + bar.get_width()/2, val + err + 2.0, f"{val:.1f}%",
             ha="center", va="bottom", fontsize=12.5, fontweight="bold", color="#334155")

fig1.tight_layout()
p1 = FIGURES_DIR / "05_rag_vs_norag_accuracy.png"
fig1.savefig(p1, dpi=DPI, bbox_inches="tight")
plt.close(fig1)
print(f"✅ [300 DPI] Saved Figure 1 → {p1}")

# =============================================================
# FIGURE 2 (06): Performance Across 7 Domains (300 DPI)
# =============================================================
ordered_categories = [
    "Commercialization Pathways & Startup Formation",
    "Federal Funding, SBIR/STTR & Grants",
    "Ethics, Conflict of Interest & University Facilities",
    "Public Disclosure & Patent Timelines",
    "UM System Patent & IP Regulations",
    "UMKC Startup Licensing Guidelines",
    "Invention Disclosure Process & Form UM-16C",
]

rag_cat_recalls = []
norag_cat_recalls = []

for cat in ordered_categories:
    sub = [r for r in results if r["category"] == cat]
    if sub:
        rag_cat_recalls.append(sum(r["rag"]["recall"] for r in sub) / len(sub) * 100)
        norag_cat_recalls.append(sum(r["norag"]["recall"] for r in sub) / len(sub) * 100)
    else:
        rag_cat_recalls.append(0.0)
        norag_cat_recalls.append(0.0)

fig2, ax2 = plt.subplots(figsize=(12.5, 6.8))
y = np.arange(len(ordered_categories))
h = 0.36
b1 = ax2.barh(y + h/2, rag_cat_recalls, h, label="RAG (+ Output Guard)", color=UMKC_BLUE, zorder=3)
b2 = ax2.barh(y - h/2, norag_cat_recalls, h, label="No-RAG (+ Output Guard)", color=GRAY, zorder=3)

# Shaded grouping
ax2.axhspan(1.5, 6.5, color="#eaf2f8", alpha=0.5, zorder=0)
ax2.text(88, 4.0, "UMKC IP &\nPOLICY CORE\n(RAG Dominates)", fontsize=11, fontweight="bold",
         color=UMKC_BLUE, ha="center", va="center",
         bbox=dict(boxstyle="round,pad=0.4", fc="white", ec=UMKC_BLUE, lw=1.2))

ax2.text(88, 0.5, "EXTERNAL TOPICS\n(Staff Deflection)", fontsize=10, fontweight="bold",
         color="#7f8c8d", ha="center", va="center",
         bbox=dict(boxstyle="round,pad=0.3", fc="white", ec="#bdc3c7", lw=1))

ax2.set_xlabel("Key Policy Facts Recall (%)", fontsize=13, fontweight="bold", color=UMKC_DARK)
ax2.set_title("Information Retrieval Accuracy Across 7 University Domains (N=50)",
              fontsize=16, fontweight="bold", color=UMKC_DARK, pad=16)
ax2.set_yticks(y)
clean_labels = [
    "Commercialization Pathways\n(General Venture Advice)",
    "Federal Funding, SBIR/STTR\n(External Agency Rules)",
    "Ethics, Conflict of Interest\n& Facilities",
    "Public Disclosure &\nPatent Timelines",
    "UM System Patent & IP\nRegulations (CRR 100.020)",
    "UMKC Startup Licensing\nGuidelines",
    "Invention Disclosure Process\n& Form UM-16C"
]
ax2.set_yticklabels(clean_labels, fontsize=11, fontweight="bold", color="#1e293b")
ax2.set_xlim(0, 100)
ax2.set_xticks([0, 20, 40, 60, 80, 100])
ax2.set_xticklabels(["0%", "20%", "40%", "60%", "80%", "100%"], fontsize=11)
ax2.xaxis.grid(True, linestyle="--", alpha=0.5, zorder=0)
ax2.legend(frameon=True, facecolor="white", edgecolor="#cbd5e1", loc="lower right", fontsize=11.5)

for bar, val in zip(b1, rag_cat_recalls):
    ax2.text(val + 1.2, bar.get_y() + bar.get_height()/2, f"{val:.1f}%",
             ha="left", va="center", fontsize=11.5, fontweight="bold", color=UMKC_BLUE)

for bar, val in zip(b2, norag_cat_recalls):
    ax2.text(val + 1.2, bar.get_y() + bar.get_height()/2, f"{val:.1f}%",
             ha="left", va="center", fontsize=11, color="#334155")

fig2.tight_layout()
p2 = FIGURES_DIR / "06_rag_vs_norag_by_category.png"
fig2.savefig(p2, dpi=DPI, bbox_inches="tight")
plt.close(fig2)
print(f"✅ [300 DPI] Saved Figure 2 → {p2}")

# =============================================================
# FIGURE 3 (07): Grounding vs. Ungrounded Risk (300 DPI)
# =============================================================
rag_ungrounded = (1.0 - (rag_cite_rate / 100)) * 100
norag_ungrounded = (1.0 - (norag_cite_rate / 100)) * 100
risk_reduction = norag_ungrounded - rag_ungrounded

fig3, ax3 = plt.subplots(figsize=(8.5, 5.8))
modes = ["RAG System\n(+ Output Guard)", "No-RAG Baseline\n(+ Output Guard)"]
rates = [rag_ungrounded, norag_ungrounded]
colors = [GREEN, RED]

bars = ax3.bar(modes, rates, color=colors, width=0.42, zorder=3)
ax3.set_ylabel("Ungrounded / Unverified Response Rate (%)", fontsize=13, fontweight="bold", color=UMKC_DARK)
ax3.set_title("Policy Verification Failure Rate: RAG vs. No-RAG Baseline (N=50)",
              fontsize=15, fontweight="bold", color=UMKC_DARK, pad=16)
ax3.set_ylim(0, 105)
ax3.set_yticks([0, 20, 40, 60, 80, 100])
ax3.set_yticklabels(["0%", "20%", "40%", "60%", "80%", "100%"], fontsize=12)
ax3.yaxis.grid(True, linestyle="--", alpha=0.5, zorder=0)

for bar, val in zip(bars, rates):
    ax3.text(bar.get_x() + bar.get_width()/2, val + 2.0, f"{val:.1f}%",
             ha="center", va="bottom", fontsize=13, fontweight="bold", color="#1e293b")

# Clean reduction callout
ax3.annotate(f"−{risk_reduction:.1f}% Risk Reduction",
             xy=(0, rag_ungrounded), xytext=(0.35, 55),
             arrowprops=dict(facecolor=GREEN, shrink=0.08, width=2.2, headwidth=7),
             fontweight="bold", color=GREEN, fontsize=12,
             bbox=dict(boxstyle="round,pad=0.4", fc="#eafaf1", ec=GREEN, lw=1.3))

fig3.tight_layout()
p3 = FIGURES_DIR / "07_rag_vs_norag_hallucination.png"
fig3.savefig(p3, dpi=DPI, bbox_inches="tight")
plt.close(fig3)
print(f"✅ [300 DPI] Saved Figure 3 → {p3}")

# =============================================================
# FIGURE 4 (08): Retrieval Latency vs Accuracy (300 DPI)
# =============================================================
rag_lat = sum(r["rag"]["latency"] for r in results) / N
norag_lat = sum(r["norag"]["latency"] for r in results) / N

fig4, ax4_1 = plt.subplots(figsize=(8.5, 5.5))
ax4_2 = ax4_1.twinx()

x = np.arange(2)
w = 0.32
b_acc = ax4_1.bar(x - w/2, [avg_rag_recall, avg_norag_recall], w, label="Accuracy (%)", color=UMKC_BLUE, zorder=3)
b_lat = ax4_2.bar(x + w/2, [rag_lat, norag_lat], w, label="Latency (s)", color=GOLD, zorder=3)

ax4_1.set_ylabel("Fact Recall Accuracy (%)", color=UMKC_BLUE, fontsize=13, fontweight="bold")
ax4_2.set_ylabel("Average Latency (seconds)", color="#8b6508", fontsize=13, fontweight="bold")
ax4_1.set_xticks(x)
ax4_1.set_xticklabels(["RAG System", "No-RAG Baseline"], fontsize=12.5, fontweight="bold", color="#1e293b")
ax4_1.set_title("System Tradeoff: Retrieval Accuracy vs. Response Time (N=50)",
                fontsize=15, fontweight="bold", color=UMKC_DARK, pad=16)
ax4_1.set_ylim(0, 100)
ax4_2.set_ylim(0, 30)
ax4_1.yaxis.grid(True, linestyle="--", alpha=0.5, zorder=0)

for bar, val in zip(b_acc, [avg_rag_recall, avg_norag_recall]):
    ax4_1.text(bar.get_x() + bar.get_width()/2, val + 1.8, f"{val:.1f}%",
               ha="center", va="bottom", fontsize=12, fontweight="bold", color=UMKC_BLUE)

for bar, val in zip(b_lat, [rag_lat, norag_lat]):
    ax4_2.text(bar.get_x() + bar.get_width()/2, val + 0.8, f"{val:.2f}s",
               ha="center", va="bottom", fontsize=12, fontweight="bold", color="#8b6508")

fig4.tight_layout()
p4 = FIGURES_DIR / "08_rag_vs_norag_latency.png"
fig4.savefig(p4, dpi=DPI, bbox_inches="tight")
plt.close(fig4)
print(f"✅ [300 DPI] Saved Figure 4 → {p4}")

# Copy all to Artifacts directory
for p in [p1, p2, p3, p4]:
    target = ARTIFACTS_DIR / p.name
    with open(p, "rb") as src, open(target, "wb") as dst:
        dst.write(src.read())

print(f"\n🎉 All 4 figures re-rendered at {DPI} DPI and copied to {ARTIFACTS_DIR}")
