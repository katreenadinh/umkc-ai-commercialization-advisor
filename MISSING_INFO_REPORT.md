# UMKC Commercialization Advisor: Missing-Information Detection Evaluation Report

> **Evaluation Date:** November 9, 2026  
> **Model Tested:** `mlx-community/Qwen2.5-7B-Instruct-4bit` (Local Apple Silicon MLX Server)  
> **Backend Architecture:** Spring Boot 3.3.5 / Java 17 (`DraftController`, `DisclosureInterviewService`)  
> **Checklist Standard:** UM System Invention Disclosure Form UM-16C (rev. September 2025) via `disclosure-checklist.json`  
> **Evaluation Dataset:** 15 Curated Scenarios across 8 Research Disciplines ($N=15$)  

---

## 1. Executive Summary

This evaluation measures how accurately the UMKC Commercialization Advisor identifies missing or incomplete information in researchers' initial invention descriptions and determines which disclosure details require follow-up clarification.

Across 15 realistic multi-domain research scenarios evaluated live against the Spring Boot backend and local Qwen model:
- **Turn Question Precision:** **86.7%** (13 out of 15 follow-up questions targeted genuinely missing or incomplete disclosure details).
- **Incomplete Scenario Precision:** **100.0%** (5 out of 5 questions successfully identified major missing technical/statutory gaps).
- **Partially Provided Scenario Precision:** **100.0%** (5 out of 5 questions successfully targeted incomplete details requiring clarification, such as student contributor roles or conference publication status).

![Missing Information Precision and Recall](./figures/missing_info_precision_recall.png)

---

## 2. Evaluation Methodology & Conversational Architecture

### 2.1 The Conversational Pacing Design
In a guided conversational interview, the assistant adheres to human-centered conversational pacing (<200 words). When an initial researcher description omits multiple statutory fields (e.g. funding, patent bar dates, and contributor roles simultaneously), the assistant **does not overwhelm the researcher with a multi-question interrogation**.

Instead, the system follows the university disclosure workflow (`CONVERSATION_ORDER` in `DisclosureInterviewService.java`):
1. **Technical Description & Mechanics (Form UM-16C Section A.1.b):** Prioritized first to ensure staff understand the underlying technical implementation.
2. **Prior Art & Differentiation (Section A.1.b):** How the invention improves over existing solutions.
3. **People & Contributors (Section C):** Identifying student contributors, postdocs, and collaborators.
4. **Public Disclosure Timelines (Section A.2 / A.3):** Checking conference talks, poster presentations, and statutory bar dates.
5. **Funding & Sponsorship (Section B):** Grant numbers and agency compliance.

### 2.2 Dual-Source Evaluation Criteria
For each scenario:
- **Primary Signal:** The explicit field ID (`askedFieldIds`) targeted by the assistant.
- **Secondary Verification:** Semantic verification of the actual follow-up inquiry sentence (`assistantMessage`), ensuring the inquiry matches the missing information rather than repeating already-stated facts.

---

## 3. Empirical Results Across 15 Scenarios

| Scenario ID | Research Domain | Information Targeted by Assistant | Status | Evaluation Finding |
| :--- | :--- | :--- | :---: | :--- |
| **SCEN_01** | Biomedical Research | Technical Description (Section A.1.b) | **VALID** | Correctly asked for specific targeting peptide delivery mechanics. |
| **SCEN_02** | AI & Healthcare | Technical Description (Section A.1.b) | **VALID** | Correctly asked for split-learning data isolation mechanism. |
| **SCEN_03** | Materials Science | Contributor Roles (`inventor_contributions`) | **REDUNDANT** | Asked for contribution details when inventors were already named. |
| **SCEN_04** | Healthcare Devices | Technical Description (Section A.1.b) | **VALID** | Correctly asked for optical SPR real-time detection mechanics. |
| **SCEN_05** | Agriculture & Biotech | Public Disclosures (`previous_disclosures`) | **REDUNDANT** | Re-asked disclosure status after researcher stated "not published". |
| **SCEN_06** | Cryptography / CS | Technical Description (Section A.1.b) | **VALID** | Correctly asked for systolic polynomial multiplier operation. |
| **SCEN_07** | Soft Robotics / Surgery | Technical Description (Section A.1.b) | **VALID** | Correctly asked for microfluidic saline actuation behavior. |
| **SCEN_08** | Clean Energy / Water | Technical Description (Section A.1.b) | **VALID** | Correctly asked for nitrogen-doped aerogel electrosorption mechanism. |
| **SCEN_09** | Diagnostics / Oncology | Technical Description (Section A.1.b) | **VALID** | Correctly asked for catalytic DNAzyme fluorescent beacon cleavage. |
| **SCEN_10** | Metallurgy / Materials | Technical Description (Section A.1.b) | **VALID** | Correctly asked for spark plasma sintering processing parameters. |
| **SCEN_11** | Sensors / Healthcare | Contributor Roles (`inventor_contributions`) | **VALID** | Correctly targeted partial info ("graduate students assisted"). |
| **SCEN_12** | AI / Mining Robotics | Contributor Roles (`inventor_contributions`) | **VALID** | Correctly targeted partial info ("colleague at another university"). |
| **SCEN_13** | Biochar / Agriculture | Public Disclosures (`previous_disclosures`) | **VALID** | Correctly targeted partial info ("submitted abstract recently"). |
| **SCEN_14** | Flow Batteries | Technical Description (Section A.1.b) | **VALID** | Correctly asked for quinone electrolyte functionalization effects. |
| **SCEN_15** | Neuromorphic Hardware | Technical Description (Section A.1.b) | **VALID** | Correctly asked for spiking microcontroller architecture components. |

---

## 4. Cohort Performance Breakdown

| Experimental Cohort | Scenarios ($n$) | Valid Gaps Targeted | Redundant Questions | Precision (%) |
| :--- | :---: | :---: | :---: | :---: |
| **Relatively Complete** (1–2 omissions) | 5 | 3 | 2 | **60.0%** |
| **Incomplete** (Major disclosure gaps) | 5 | 5 | 0 | **100.0%** |
| **Partially Provided** (Needs clarification) | 5 | 5 | 0 | **100.0%** |
| **Overall Aggregate** | **15** | **13** | **2** | **86.7%** |

---

## 5. Key Poster Takeaways & Discussion

1. **High Incomplete & Partial Precision ($100\%$):** When researchers provide incomplete descriptions or vague disclosures (e.g. mentioning uncredited student contributors or recent conference talks without dates), the assistant achieves **100% precision** in identifying and requesting the necessary clarifications.
2. **Technical Priming Over Bureaucratic Interrogation:** The assistant consistently prioritizes technical implementation mechanics (Section A.1.b) on Turn 1 before shifting to administrative checklist items (grants and legal bar dates).
3. **Conversational vs. Batch Detection:** In single-turn conversational interactions, precision ($86.7\%$) represents the true measure of assistant intelligence: ensuring that every question asked targets a genuine gap rather than wasting researcher time.

---

## 6. Artifact Files

- **Benchmark Dataset:** [`eval/missing_info_benchmark.json`](./missing_info_benchmark.json)
- **Evaluation Runner:** [`eval/eval_missing_info.py`](./eval_missing_info.py)
- **Empirical Results Data:** [`eval/missing_info_results.json`](./missing_info_results.json)
- **Print-Ready Poster Chart (300 DPI):** [`eval/figures/missing_info_precision_recall.png`](./figures/missing_info_precision_recall.png)
