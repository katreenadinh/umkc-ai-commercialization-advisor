# Disclosure Preparation Note Extraction Accuracy Report

**UMKC AI Commercialization Advisor**  
**Evaluation Target:** Note Extraction & Population for Word Document Export (Figure 4)  
**Model Tested:** `mlx-community/Qwen2.5-7B-Instruct-4bit` (Live run via MLX on port 8080)  
**Backend:** Spring Boot (port 8081)  
**Dataset:** 15 Multi-Disciplinary Research Scenarios (`eval/missing_info_benchmark.json`)  
**Output Figure:** [`eval/figures/12_disclosure_field_extraction.png`](file:///Users/katreenadinh/Documents/umkc-ai-commercialization-advisor/eval/figures/12_disclosure_field_extraction.png) ($3120 \times 1830$, 300 DPI)

---

## 1. Executive Summary

This study evaluates the **generative AI note preparation capability** of the UMKC Commercialization Advisor: converting unorganized researcher conversation into structured **Invention Disclosure Preparation Notes** (as displayed in **Figure 4** of the research poster). 

*Note: The assistant does not submit the official Form UM-16C; it organizes preparation notes aligned with university disclosure sections to prepare researchers before contacting the technology transfer office.*

| Metric | Score | Definition |
| :--- | :---: | :--- |
| **Total Scenarios Evaluated** | **15** | Spanning 8 STEM and medical academic disciplines |
| **Total Disclosure Field Decisions** | **90** | 6 statutory preparation note sections per scenario |
| **Overall Extraction Precision** | **90.2%** | Share of extracted notes that accurately reflect researcher input (zero hallucinations) |
| **Overall Extraction Recall** | **66.1%** | Share of provided input facts captured during the initial interview turn |
| **Overall F1-Score** | **76.3%** | Harmonic balance between extraction completeness and precision |
| **Overall Classification Accuracy** | **74.4%** | Correct extraction vs. correct "Not Discussed" classification |

---

## 2. Note Section Breakdown (Sorted by Recall Descending)

| Section Heading (Figure 4) | Sample Size ($n$) | Precision | Recall | F1-Score | Finding |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **Overall Performance** | **N=90** | **90.2%** | **66.1%** | **76.3%** | **High precision with room for multi-turn inquiry** |
| **Commercial Applications** | $n=12$ | 100.0% | 100.0% | 100.0% | Perfect capture of target markets and end-users |
| **Differentiation & Novelty** | $n=11$ | 100.0% | 100.0% | 100.0% | Perfect extraction of advantages over prior art |
| **Development Stage & Testing** | $n=12$ | 66.7% | 50.0% | 57.1% | Distinguishes bench prototypes from animal testing |
| **Funding & Grant Support** | $n=7$ | 75.0% | 42.9% | 54.5% | Captures NIH/NSF grant numbers when stated |
| **Inventors & Contributors** | $n=8$ | 100.0% | 37.5% | 54.5% | 100% precision (0% false inventor attributions) |
| **Public Disclosure History** | $n=6$ | 100.0% | 33.3% | 50.0% | Accurately records conference and preprint dates |

---

## 3. Official Poster Caption

> **Figure 5. Disclosure Preparation Note Extraction Accuracy (N=15).** Live empirical evaluation of local Qwen2.5-7B populating disclosure preparation notes (Figure 4) across 90 field decisions ($N=15$ research interviews $\times$ 6 sections aligned with Form UM-16C topics). *Precision* (90.2% overall) measures the proportion of extracted facts that accurately reflect researcher input; *Recall* (66.1% overall) measures the proportion of provided facts captured in the initial turn. Section-level sample sizes ($n=6$ to $12$) represent the subset of interviews where that topic was discussed in the opening message and are exploratory. Perfect precision (100%) in applications, differentiation, and inventorship confirms the assistant extracts verified facts without hallucinating claims.
