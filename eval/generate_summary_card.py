import os
os.environ["MPLCONFIGDIR"] = "/tmp/mpl_cache"

from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as patches

FIGURES_DIR = Path("eval/figures")
FIGURES_DIR.mkdir(parents=True, exist_ok=True)
ARTIFACTS_DIR = Path("/Users/katreenadinh/.gemini/antigravity/brain/67b83cd1-c456-4497-9b2e-891709b1e77e")
OUT_FILE = FIGURES_DIR / "10_system_evaluation_summary.png"

# Palette matching user's poster
UMKC_BLUE = "#004b87"
UMKC_DARK = "#0a2540"
TEXT_DARK = "#1e293b"
GRAY_TEXT = "#475569"
DPI = 300

fig, ax = plt.subplots(figsize=(10.5, 4.6), dpi=DPI)
ax.set_facecolor("#ffffff")
ax.set_xlim(0, 10.5)
ax.set_ylim(0, 4.6)
ax.axis("off")

# Title Banner
ax.text(5.25, 4.25, "System Evaluation & Compliance Summary",
        ha="center", va="center", fontsize=15, fontweight="bold", color=UMKC_DARK)

# 3 Stat Cards across top (y: 2.5 to 3.85)
card_defs = [
    ("100.0%", "Safety & Legal Compliance", "0 unauthorized legal determinations;\nall status inquiries safely referred to OTC.", "#166534", "#f0fdf4", "#bbf7d0"),
    ("3.1", "Citation Depth / Density", "Average official sources cited per answer\n(125 total statutory citations vs. 0).", UMKC_BLUE, "#eff6ff", "#bfdbfe"),
    ("86.7%", "Inquiry Gap Precision", "Follow-up questions accurately targeted\nreal gaps (100% on incomplete inputs).", "#0369a1", "#f0f9ff", "#bae6fd"),
]

x_starts = [0.4, 3.8, 7.2]
card_w = 2.9
card_h = 1.35

for x_s, (stat, title, desc, col, bg_col, bdr_col) in zip(x_starts, card_defs):
    # Rounded card box
    rect = patches.FancyBboxPatch((x_s, 2.5), card_w, card_h,
                                 boxstyle="round,pad=0.08,rounding_size=0.15",
                                 facecolor=bg_col, edgecolor=bdr_col, linewidth=1.2, zorder=1)
    ax.add_patch(rect)
    
    cx = x_s + card_w / 2
    ax.text(cx, 3.48, stat, ha="center", va="center", fontsize=18, fontweight="bold", color=col, zorder=2)
    ax.text(cx, 3.12, title, ha="center", va="center", fontsize=10, fontweight="bold", color=TEXT_DARK, zorder=2)
    ax.text(cx, 2.76, desc, ha="center", va="center", fontsize=7.8, color=GRAY_TEXT, linespacing=1.2, zorder=2)

# Divider line
ax.plot([0.4, 10.1], [2.25, 2.25], color="#e2e8f0", lw=1.2)

# Performance Comparison Table (y: 0.2 to 2.1)
headers = ["Evaluation Metric (N=50)", "RAG Advisor", "No-RAG Baseline", "Measured Impact"]
col_x = [0.5, 4.8, 6.7, 8.8]

# Header Row Box
h_rect = patches.FancyBboxPatch((0.4, 1.85), 9.7, 0.32,
                                boxstyle="round,pad=0.02,rounding_size=0.08",
                                facecolor="#f1f5f9", edgecolor="#cbd5e1", linewidth=1.0)
ax.add_patch(h_rect)

for h_text, x_pos in zip(headers, col_x):
    ax.text(x_pos, 2.01, h_text, ha="left" if x_pos == 0.5 else "center", va="center",
            fontsize=9.5, fontweight="bold", color=UMKC_DARK)

rows = [
    ("Policy Citation Grounding Rate", "80.0%", "0.0%", "+80.0% Verifiable Source Grounding"),
    ("Patent Regulations Recall (CRR 100.020)", "21.7%", "12.5%", ">1.7x Higher Subdomain Recall"),
    ("Invention Disclosure Form Recall (UM-16C)", "35.4%", "32.1%", "+3.3% Procedural Accuracy"),
    ("Average Latency (8 Vector Chunks)", "21.71 s", "13.51 s", "+8.20 s Search & Citation Overhead"),
]

row_y = [1.52, 1.15, 0.78, 0.41]

for (m_title, rag_v, norag_v, impact_v), y_pos in zip(rows, row_y):
    ax.text(col_x[0], y_pos, m_title, ha="left", va="center", fontsize=9.2, fontweight="bold", color=TEXT_DARK)
    ax.text(col_x[1], y_pos, rag_v, ha="center", va="center", fontsize=9.5, fontweight="bold", color=UMKC_BLUE)
    ax.text(col_x[2], y_pos, norag_v, ha="center", va="center", fontsize=9.2, color="#64748b")
    ax.text(col_x[3], y_pos, impact_v, ha="center", va="center", fontsize=8.8, color=GRAY_TEXT, fontstyle="italic")
    # subtle row divider
    if y_pos > 0.5:
        ax.plot([0.4, 10.1], [y_pos - 0.18, y_pos - 0.18], color="#f1f5f9", lw=1.0)

fig.tight_layout()
fig.savefig(OUT_FILE, dpi=DPI, bbox_inches="tight")
plt.close(fig)
print(f"✅ Saved clean 300 DPI Summary Card → {OUT_FILE}")

# Copy to artifacts
target = ARTIFACTS_DIR / "10_system_evaluation_summary.png"
with open(OUT_FILE, "rb") as src, open(target, "wb") as dst:
    dst.write(src.read())
print(f"✅ Copied to artifacts → {target}")
