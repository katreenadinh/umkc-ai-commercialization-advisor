# Safety & Compliance Screening Evaluation Report

**UMKC AI Commercialization Advisor**  
**Evaluation Target:** Relevance and Safety Screening Firewall (`ReferralService.java`)  
**Methodology Component:** Step 2 & System Architecture Component 1  
**Dataset:** 40 Predefined Prompts across 5 Risk & Control Categories (`eval/safety_firewall_benchmark.json`)  
**Output Figure:** [`eval/figures/11_safety_firewall_interception.png`](file:///Users/katreenadinh/Documents/umkc-ai-commercialization-advisor/eval/figures/11_safety_firewall_interception.png) ($3192 \times 1920$, 300 DPI)

---

## 1. Executive Summary

| Metric | Score | Detail |
| :--- | :---: | :--- |
| **Total Test Scenarios** | **40** | 30 Risk Scenarios + 10 Benign Control Scenarios |
| **Risk Detection Sensitivity (Recall)** | **100.0%** | **30/30** legal determinations, imminent disclosures, and injection attacks intercepted |
| **Control Specificity (Benign Pass-Through)** | **100.0%** | **10/10** benign policy questions passed without false suppression |
| **Overall Firewall Accuracy** | **100.0%** | **40/40** correct routing actions |
| **Unauthorized Legal Advice Escapes** | **0.0%** | 0 determinations permitted to reach LLM generation |

---

## 2. Category Performance Breakdown

| Category | Sample Size ($n$) | Primary Action | Accuracy / Interception | Safety Objective |
| :--- | :---: | :--- | :---: | :--- |
| **Imminent Public Disclosure** | 10 | `BLOCK_AND_REFER` | **100.0%** (10/10) | Prevents loss of international patent rights and triggers statutory bar date warning |
| **Legal & IP Determinations** | 10 | `FLAG_AND_REFER` | **100.0%** (10/10) | Refers patentability, ownership, and inventorship claims to university tech transfer staff |
| **Past Public Disclosure** | 6 | `FLAG_AND_REFER` | **100.0%** (6/6) | Detects prior public sharing and warns of 1-year US grace period countdown |
| **Prompt Injection Defense** | 4 | `BLOCK_AND_REFER` | **100.0%** (4/4) | Defends against system prompt extraction and instruction overrides |
| **Benign Policy Questions (Control)** | 10 | `ALLOW_RAG` | **100.0%** (10/10) | Ensures legitimate educational inquiries flow unimpeded to RAG pipeline |

---

## 3. Key Findings for Poster Presentation

1. **Direct Validation of Figure 3 (Working Chat Interaction):**  
   In Figure 3 of the poster, the researcher asks: *"Before that I want to present my work at a conference. Am I able to do so?"*  
   The evaluation proves that this behavior is systematic: all 10 imminent disclosure prompts (conferences, preprints on arXiv, journal publications, demos) are intercepted with 100% sensitivity, halting LLM generation and warning the user of the critical statutory bar window.

2. **Zero Unauthorized Legal Determinations:**  
   The firewall prevents the model from adjudicating patentability ("Is this patentable?"), ownership ("Who owns my software?"), or inventorship ("Should my student be listed?"), maintaining strict compliance with university governance and licensing policy (CRR 100.020).

3. **Zero False Suppression on General Inquiries:**  
   Educational questions regarding CRR 100.020, invention disclosure definitions, and commercialization pathways achieved 100% specificity, ensuring researchers are not blocked from learning university policy.

---

## 4. Caption for Poster

> **Figure 5. Safety and compliance screening performance across 40 evaluation prompts.** A deterministic Java firewall (`ReferralService.java`) screens user messages before LLM invocation, achieving 100% interception of imminent disclosure risks, patentability determinations, and prompt injections, while maintaining 100% pass-through for general university policy questions.
