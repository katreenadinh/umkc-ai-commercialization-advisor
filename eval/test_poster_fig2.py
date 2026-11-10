import os
os.environ["MPLCONFIGDIR"] = "/tmp/mpl_cache"

import json
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

FIGURES_DIR = Path("eval/figures")
ARTIFACTS_DIR = Path("/Users/katreenadinh/.gemini/antigravity/brain/67b83cd1-c456-4497-9b2e-891709b1e77e")
RESULTS_FILE = Path("eval/rag_vs_norag_results.json")

# Unified Academic Palette
UMKC_BLUE = "#004b87"
UMKC_DARK = "#0a2540"
GRAY = "#708090"
DPI = 300

with open(RESULTS_FILE) as f:
    results = json.load(f)

# Ordered from lowest to highest for clean horizontal bar presentation
ordered_categories = [
    "Federal Funding, SBIR/STTR & Grants",
    "Ethics, Conflict of Interest & University Facilities",
    "Commercialization Pathways & Startup Formation",
    "UM System Patent & IP Regulations",
    "Public Disclosure & Patent Timelines",
    "UMKC Startup Licensing Guidelines",
    "Invention Disclosure Process & Form UM-16C",
]

category_display = {
    "Federal Funding, SBIR/STTR & Grants": "Federal Funding, SBIR/STTR (n=6)",
    "Ethics, Conflict of Interest & University Facilities": "Ethics, Conflict of Interest & Facilities (n=6)",
    "Commercialization Pathways & Startup Formation": "Commercialization Pathways & Startups (n=6)",
    "UM System Patent & IP Regulations": "UM System Patent Regulations (CRR 100.020, n=10)",
    "Public Disclosure & Patent Timelines": "Public Disclosure & Timelines (n=6)",
    "UMKC Startup Licensing Guidelines": "UMKC Startup Licensing Guidelines (n=8)",
    "Invention Disclosure Process & Form UM-16C": "Invention Disclosure & Form UM-16C (n=8)",
}

rag_cat_recalls = []
norag_cat_recalls = []
labels = []

for cat in ordered_categories:
    sub = [r for r in results if r["category"] == cat]
    labels.append(category_display[cat])
    rag_cat_recalls.append(sum(r["rag"]["recall"] for r in sub) / len(sub) * 100)
    norag_cat_recalls.append(sum(r["norag"]["recall"] for r in sub) / len(sub) * 100)

fig, ax = plt.subplots(figsize=(11.5, 6.5))
y = np.arange(len(ordered_categories))
h = 0.35

b1 = ax.barh(y + h/2, rag_cat_recalls, h, label="RAG (+ Output Guard)", color=UMKC_BLUE, zorder=3)
b2 = ax.barh(y - h/2, norag_cat_recalls, h, label="No-RAG Baseline (+ Output Guard)", color=GRAY, zorder=3)

# Clear academic title (no brackets, no promotional phrases)
ax.set_title("Information Retrieval Accuracy Across 7 University Domains (N=50)",
             fontsize=15.5, fontweight="bold", color=UMKC_DARK, pad=16)

ax.set_xlabel("Policy Fact Recall (%)", fontsize=13, fontweight="bold", color=UMKC_DARK)
ax.set_yticks(y)
ax.set_yticklabels(labels, fontsize=11.5, fontweight="bold", color="#1e293b")

# Strict 0-100% axis cap
ax.set_xlim(0, 100)
ax.set_xticks([0, 20, 40, 60, 80, 100])
ax.set_xticklabels(["0%", "20%", "40%", "60%", "80%", "100%"], fontsize=11.5)
ax.xaxis.grid(True, linestyle="--", alpha=0.5, zorder=0)

# Clean, uncluttered legend placed in spacious empty area on upper right
ax.legend(frameon=True, facecolor="white", edgecolor="#cbd5e1", loc="upper right", fontsize=12)

# Large, readable bar labels placed just outside bars
for bar, val in zip(b1, rag_cat_recalls):
    ax.text(val + 1.2, bar.get_y() + bar.get_height()/2, f"{val:.1f}%",
            ha="left", va="center", fontsize=11.5, fontweight="bold", color=UMKC_BLUE)

for bar, val in zip(b2, norag_cat_recalls):
    ax.text(val + 1.2, bar.get_y() + bar.get_height()/2, f"{val:.1f}%",
            ha="left", va="center", fontsize=11, color="#334155")

# Spines styling
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
ax.spines["left"].set_color("#94a3b8")
ax.spines["bottom"].set_color("#94a3b8")

fig.tight_layout()
p2 = FIGURES_DIR / "06_rag_vs_norag_by_category.png"
fig.savefig(p2, dpi=DPI, bbox_inches="tight")
plt.close(fig)
print(f"✅ Saved clean Poster Figure 2 → {p2}")

# Copy to artifacts
target = ARTIFACTS_DIR / "06_rag_vs_norag_by_category.png"
with open(p2, "rb") as src, open(target, "wb") as dst:
    dst.write(src.read())
print(f"✅ Copied to artifacts → {target}")
