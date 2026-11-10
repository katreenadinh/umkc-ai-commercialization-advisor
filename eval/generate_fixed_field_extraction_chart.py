#!/usr/bin/env python3
"""
Generates the clean, decluttered Disclosure Preparation Note Extraction Accuracy chart
matching the exact section headings of Figure 4 (Word document download).
"""

import os
os.environ.setdefault("MPLCONFIGDIR", "/tmp/mpl_cache")
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

CHART_FILE = "eval/figures/12_disclosure_field_extraction.png"
ARTIFACT_FILE = "/Users/katreenadinh/.gemini/antigravity/brain/67b83cd1-c456-4497-9b2e-891709b1e77e/12_disclosure_field_extraction.png"

# Official UMKC Academic Palette
UMKC_BLUE = "#004b87"
UMKC_DARK = "#0a2540"
GRAY = "#708090"

# Clean row labels matching Figure 4, sorted strictly by Recall descending
rows = [
    {
        "label": "Overall Performance",
        "precision": 90.2,
        "recall": 66.1,
    },
    {
        "label": "Commercial Applications",
        "precision": 100.0,
        "recall": 100.0,
    },
    {
        "label": "Differentiation & Novelty",
        "precision": 100.0,
        "recall": 100.0,
    },
    {
        "label": "Development Stage & Testing",
        "precision": 66.7,
        "recall": 50.0,
    },
    {
        "label": "Funding & Grant Support",
        "precision": 75.0,
        "recall": 42.9,
    },
    {
        "label": "Inventors & Contributors",
        "precision": 100.0,
        "recall": 37.5,
    },
    {
        "label": "Public Disclosure History",
        "precision": 100.0,
        "recall": 33.3,
    }
]

labels = [r["label"] for r in rows]
precisions = [r["precision"] for r in rows]
recalls = [r["recall"] for r in rows]

fig, ax = plt.subplots(figsize=(10.5, 6.2), dpi=300)

y = np.arange(len(labels))
h = 0.36

# Top bar: Precision (Blue). Bottom bar: Recall (Gray).
b_prec = ax.barh(y - h/2, precisions, h, label="Precision", color=UMKC_BLUE, zorder=3)
b_rec = ax.barh(y + h/2, recalls, h, label="Recall", color=GRAY, zorder=3)

# Subtle separator line under Overall Performance
ax.axhline(0.5, color="#cbd5e0", linestyle="-", linewidth=1.2, zorder=2)

ax.set_yticks(y)
# Clean, bold 13.5pt font for maximum legibility at poster distance
ax.set_yticklabels(labels, fontsize=13.0, fontweight="bold", color=UMKC_DARK)
ax.invert_yaxis()

# Strictly capped at 100
ax.set_xlim(0, 100)
ax.set_xticks([0, 20, 40, 60, 80, 100])
ax.set_xlabel("Extraction Metric Score (%)", fontsize=13.0, fontweight="bold", color=UMKC_DARK, labelpad=8)

# Clean, authentic title
ax.set_title("Disclosure Preparation Note Extraction Accuracy (N=15)",
             fontsize=15.5, fontweight="bold", color=UMKC_DARK, pad=32, loc="center")

# Gridlines and spines
ax.grid(axis='x', linestyle='--', alpha=0.35, zorder=0)
ax.set_axisbelow(True)
ax.spines['top'].set_visible(False)
ax.spines['right'].set_visible(False)
ax.spines['left'].set_color('#cbd5e0')
ax.spines['bottom'].set_color('#cbd5e0')
ax.tick_params(axis='x', labelsize=11.5)

# Add data labels inside the bars
for bar, val in zip(b_prec, precisions):
    ax.text(val - 2.2, bar.get_y() + bar.get_height() / 2, f"{val:.1f}%",
            va="center", ha="right", fontsize=11.0, fontweight="bold", color="#ffffff", zorder=5)

for bar, val in zip(b_rec, recalls):
    ax.text(val - 2.2, bar.get_y() + bar.get_height() / 2, f"{val:.1f}%",
            va="center", ha="right", fontsize=11.0, fontweight="bold", color="#ffffff", zorder=5)

# Highlight overall label text
yticks = ax.get_yticklabels()
yticks[0].set_color("#003366")
yticks[0].set_fontsize(13.5)

# Clean legend centered ABOVE the plot area
ax.legend(loc="lower center", bbox_to_anchor=(0.5, 1.01), ncol=2, frameon=True, framealpha=0.96,
          facecolor="#ffffff", edgecolor="#cbd5e0", fontsize=12.0, prop={'weight': 'bold'})

plt.tight_layout()

# Save figures
plt.savefig(CHART_FILE, bbox_inches="tight", dpi=300)
plt.savefig(ARTIFACT_FILE, bbox_inches="tight", dpi=300)
plt.close()

print(f"Successfully generated clean chart: {CHART_FILE}")
