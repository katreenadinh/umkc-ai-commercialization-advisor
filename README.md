# UMKC AI Commercialization Advisor (Hackaroo prototype)

An educational and preparation tool that helps researchers understand IP, technology commercialization and the invention
disclosure process. It is **not** a lawyer, a patent examiner, or an official submission channel, and it has **not** been
approved for confidential university research. Use synthetic or non-confidential information only.

## Status

| Stage | Status |
|---|---|
| 1-3 Scaffold, source verification, relevance check, Java referral rules, output guard | done, unit tested |
| 4 RAG: ingestion, pgvector retrieval, grounded answers, Java-built citations | done, unit tested |
| 5 (backend) Guided interview, completeness review, preparation summary, DOCX export, drafts | done, unit tested (see "Verification") |
| 5 (frontend) Interview, review dashboard, summary editor | UI ported from `umkc-commercialization-advisor`; coach/sources talk to this Java API |
| 7 Colab notebook, 8 evaluation set, 9 final docs | not started |

## Architecture
- Backend: Java 17, Spring Boot 3, Maven, PostgreSQL + pgvector. Frontend: React + Tailwind (to be built/integrated).
- **LLM: local by default.** `LocalLlmClient` calls any OpenAI-compatible chat server on your machine (for example
  `mlx_lm.server` on Apple Silicon). Nothing is sent to a hosted service. A hosted Anthropic client remains available as an
  optional provider (`LLM_PROVIDER=anthropic`) and must not be used for confidential research.
- **Embeddings:** all-MiniLM-L6-v2, in-process (ONNX), separate from the chat model.
- **Safety is enforced in Java**, not in prompts: imminent public disclosure and prompt injection short-circuit before any model call;
  determination questions (patentability, ownership, inventorship, novelty/value, university decisions) always get a referral note;
  an output guard blocks definitive claims and invented URLs/emails.

## Running locally
1. Start your local model server and **confirm its real address and model name** (these are placeholders in the repo):
   `LOCAL_LLM_BASE_URL` (default `http://127.0.0.1:8080/v1`) and `LOCAL_LLM_MODEL`. If you use `mlx_lm.server`, it listens on
   port 8080 by default, so the Spring app defaults to **8081** (`SERVER_PORT`) to avoid a clash.
2. `cp .env.example .env`, set `DB_PASSWORD`, `LOCAL_LLM_MODEL`.
3. `docker compose up -d` (Postgres + pgvector). Flyway creates the schema on first start.
4. Load the knowledge base (separate from the app): `cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=ingest`.
   It fetches the sources in `backend/src/main/resources/sources.json` (UMKC pages, UM System regulation 100.020, the UM 16C form),
   plus any PDF/DOCX/TXT/MD in `data/imports/`, and prints char counts, chunk counts, warnings and a text preview.
   Notebook handoff: add `-Dspring-boot.run.arguments=--ingest.jsonl=../data/chunks.jsonl`.
5. `cd backend && mvn test && mvn spring-boot:run`.
6. Frontend: `cd frontend && npm install && npm run dev` → http://localhost:5173 (Vite proxies `/api` to port 8081).

## Checklist and the official form
`backend/src/main/resources/disclosure-checklist.json` drives the interview and review. It was built from the text of the
UM System **Invention Disclosure Form UM 16C (rev. September 2025)**. Each field records its origin:
`OFFICIAL_FORM`, `OFFICIAL_PAGE`/`OFFICIAL_REGULATION` (UMKC page, UM CRR 100.020), or `PROJECT_GUIDANCE` (helpful extras that are
**not** on the form: evidence/testing, development stage, potential applications). Inspection is **partial**, see
`inspectionStatus` in the JSON: the UMKC-hosted copy of the form and the form's instruction page were not inspected, and no
required/optional markings were seen, so no field is labelled required. Interview priority order is a project strategy, not an
official ranking.

## API (all draft endpoints need header `X-Owner-Token: <random UUID>`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/advisor/ask` | question -> routed, grounded, cited answer or referral |
| GET | `/api/sources`, `/api/sources/search?q=` | approved sources; top passages (no LLM) |
| GET | `/api/checklist` | checklist fields, form version, inspection status |
| POST | `/api/drafts` `{workflow, title?}` | create draft (`NEW_INVENTION` or `UPDATE_EXISTING`), returns opening question |
| GET | `/api/drafts`, `/api/drafts/{id}` | list / reopen (fields with answers, transcript, progress, summary) |
| DELETE | `/api/drafts/{id}` | delete |
| POST | `/api/drafts/{id}/interview/messages` `{message}` | next interview turn |
| POST | `/api/drafts/{id}/interview/skip/{fieldId}` | skip a question |
| PUT | `/api/drafts/{id}/answers/{fieldId}` `{text, mark}` | edit an answer (`mark`: NONE, NOT_YET_KNOWN, NOT_APPLICABLE, SKIPPED) |
| POST | `/api/drafts/{id}/review` | completeness review (only changed answers are re-reviewed) |
| POST / PUT | `/api/drafts/{id}/summary` | generate summary from latest answers / save the researcher's edits |
| GET | `/api/drafts/{id}/export?format=docx\|md` | download the summary |

Progress = fields addressed / (applicable fields minus those the researcher marked not applicable). "Addressed" in review means
PROVIDED, NOT_YET_KNOWN or NEEDS_HUMAN_REVIEW. It measures preparation only, never patentability, novelty or value.

## Verification (what was and was not actually run)
**Run and passing (123 JUnit tests, compiled with `javac` against JUnit 5 and Jackson):** referral rules, output guard, JSON
validators, relevance, RAG grounding and citations, chunker, HTML loader, interview flow (including synthetic scenarios: sensor
conversation, untested work, upcoming and past public disclosure, skipping, definitive patentability request, prompt injection,
malformed model output), review (statuses, caching, progress, no invented content), summary builder, DOCX export, draft ownership
and quotas, and the local LLM client against a real local HTTP server. The DOCX output was also opened with python-docx.
Mutation checks confirmed the injection-delimiter and citation-label tests fail when those protections are removed.

**Not verified (no network access to Maven Central in the build sandbox):** `mvn` build and dependency versions, Spring
controllers/config, Flyway migrations, `PgVectorStore`/`JdbcDraftRepository` SQL, the ONNX embedder, `PdfLoader`, and
`DocxLoader` (compiles against POI 4.0.1 but could not run: the packaged schema jar is incomplete). **Never run:** any prompt
against your real local model. Small local models can produce malformed JSON; Java validates, retries once, and falls back to
static checklist questions, but real-model behaviour needs your testing.
