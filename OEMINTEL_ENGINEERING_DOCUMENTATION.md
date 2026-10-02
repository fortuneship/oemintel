# OEM Intel: OEM Onboarding Agent

## Engineering Documentation

| Item | Value |
|---|---|
| Document | Engineering documentation: design, implementation, operations |
| Version | 1.0 |
| Date | 2 October 2026 |
| Status | Phase 1 in progress: foundations and a working end-to-end slice are built |
| Audience | Any engineer joining or reviewing the project |
| Repository | `oemintel` (Spring Boot application, this folder) |
| Companion files | `OEMINTEL_ENGINEERING_DOCUMENTATION.docx` (same content, Word format) |

---

## 1. Executive summary

OEM Intel is an internal tool that onboards a manufacturer's (OEM's) product catalogue into our e-commerce store. An operator asks it, for example, to "onboard Roxtec products"; the system finds the products on the OEM's website, extracts names, article numbers (SKUs), specifications, images, datasheets and certificates with full provenance, and presents everything in a review dashboard where people check, correct, approve or reject each product. Publishing approved products to Magento 2 is Phase 2.

**Key design decision:** it is a deterministic, database-backed pipeline with a tightly bounded use of an LLM, not an autonomous "agent". This makes runs reproducible, resumable, auditable and predictable in cost, which matters because the first OEM (Roxtec) sells fire-, gas- and water-rated cable and pipe seals, where a wrong specification or an outdated certificate carries real liability.

**Current state (2 October 2026):**

- The data model, job engine and JSON API (roadmap Steps 1 to 3) are built and tested.
- A thin end-to-end slice is working: a polite fetcher, a Roxtec page extractor, a catalogue writer and simple browser pages for jobs and products.
- First real run: 15 Roxtec product families fetched and extracted (59 SKUs) with no errors, requests spaced 3.3 to 4.5 seconds apart, robots.txt respected.
- 33 automated tests exist (unit, integration against real PostgreSQL, and a golden test against a saved Roxtec page).

**Biggest open item:** the legal go/no-go for crawling Roxtec at scale. The system enforces this: full crawls are refused until a crawl authorisation is recorded for the OEM; only dry runs and capped runs of at most 20 families are allowed meanwhile.

---

## 2. Getting started

### 2.1 Prerequisites

| Tool | Version used | Notes |
|---|---|---|
| JDK | 21 or newer (developed on Temurin 25) | Project targets Java 21 (`java.version` in `pom.xml`) |
| Docker | Docker Desktop 29.x | Runs PostgreSQL locally and in tests (Testcontainers) |
| Maven | Wrapper included (`mvnw`, Maven 3.9.16) | No global Maven needed |

### 2.2 Run locally

```bash
# 1. Start PostgreSQL (pgvector image, bound to 127.0.0.1:5435)
docker compose up -d

# 2. Start the application (port 8090, localhost only)
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx512m"

# 3. Open the dashboard
#    http://localhost:8090/          jobs and the "start onboarding" form
#    http://localhost:8090/products  onboarded product families
```

Flyway applies the database migrations automatically on start-up. The first OEM (Roxtec) is seeded by migration `V1`.

**Ports.** The defaults avoid ports commonly used on developer machines (a native PostgreSQL on 5432, phpMyAdmin on 8080, another service on 5433). Override with environment variables or a `.env` file next to `compose.yaml` (never commit it):

| Variable | Default | Purpose |
|---|---|---|
| `OEMINTEL_DB_PORT` | `5435` | Host port for PostgreSQL (compose) |
| `OEMINTEL_DB_URL` | `jdbc:postgresql://localhost:5435/oemintel` | JDBC URL used by the app |
| `OEMINTEL_DB_USER` / `OEMINTEL_DB_PASSWORD` | `oemintel` / `oemintel` | Database credentials |
| `OEMINTEL_PORT` | `8090` | Application HTTP port |
| `OEMINTEL_ADDRESS` | `127.0.0.1` | Bind address. Keep it local until authentication exists |
| `OEMINTEL_CRAWL_CONTACT` | empty | Optional contact appended to the crawler's User-Agent |
| `OEMINTEL_STORAGE_ROOT` | `data/blobs` | Where raw pages are stored |

### 2.3 Your first onboarding run

1. Open `http://localhost:8090/`. The text box is pre-filled with 15 Roxtec family-page URLs (from `src/main/resources/adapters/roxtec/sample-families.txt`).
2. Click **Start onboarding**. You are taken to the job page, which refreshes every 3 seconds.
3. When the job shows `COMPLETED`, open **Products**. Each family links to a detail page with every article (SKU), all specifications (hover a value to see exactly where it came from), images and document links.

### 2.4 Stopping

- Application: `Ctrl+C` in its terminal, or `taskkill /PID <pid> /F` on Windows.
- Database: `docker compose down` (data is kept in the `oemintel_postgres-data` volume).

### 2.5 Run the tests

```bash
./mvnw test
```

Tests start their own PostgreSQL containers (Testcontainers); Docker must be running. See section 14 for suites and a known slowness issue.

---

## 3. Background, goals and scope

### 3.1 Problem

Onboarding OEM products into the Magento storefront is manual: copying names, article numbers, specifications, images, datasheets and certificates from each manufacturer's site. It is slow, inconsistent, untraceable, and risky for safety-rated products.

### 3.2 Goals

1. Reduce manual effort to review and correction only.
2. Every published value is traceable to a source (URL, PDF page, excerpt) or to a named reviewer.
3. No human decision is ever silently lost when data is re-extracted.
4. The design is ready for Phase 2: Magento publishing, a chat front end, more OEMs.

### 3.3 Success metrics

| Metric | Target |
|---|---|
| SKU accuracy on the golden sample set | 100% recall and 100% precision |
| Key specification values on the golden set | 95% or better exact match |
| Precision of verified LLM facts | 98% or better |
| Average review time per product family | Under 5 minutes |
| Spurious re-reviews after an unchanged re-crawl | Zero |
| Block or challenge events during the full run | Zero |

### 3.4 Scope

| In scope (Phase 1) | Designed for, not built (Phase 2+) |
|---|---|
| Job creation (OEM, locale, limit and unit, filters, options) | Publishing approved products to Magento |
| Product discovery, fetching and extraction | Natural-language chat interface |
| Image and document download, storage and processing | OEMs beyond Roxtec |
| Normalisation against an attribute dictionary | Scheduled re-crawls and change detection |
| Review dashboard: list, detail, edit, approve and reject, progress | Spreadsheet round-trip editing |
| Re-run semantics, diffs, approval snapshots | OCR for scanned documents |

---

## 4. Feasibility, legal position and the crawl authorisation gate

### 4.1 Technical feasibility

High. 500 products means a few thousand requests: a small crawl. Reconnaissance (section 10.1) confirmed Roxtec's product pages are server-rendered, carry schema.org structured data, and were served to our bot without a challenge.

### 4.2 Legal and terms of use (not legal advice; confirm with counsel)

| Concern | Position | System response |
|---|---|---|
| robots.txt | Not binding on its own, but ignoring it weakens our position | Always obeyed (RFC 9309 via crawler-commons) |
| Website terms of use | May forbid automated collection regardless of politeness | Must be reviewed by the business owner before scale |
| Copyright (images, text, PDFs) | Facts are generally free; photos, text and documents are protected | Usage-rights status per asset; publishing (Phase 2) must respect it |
| EU database right | Roxtec is Swedish; systematic extraction of a substantial part may be covered | Uncertain; written permission resolves it |
| Product liability | Wrong ratings or stale certificates carry liability | Mandatory human review, provenance, conflict and expiry flags |

**Recommendation:** ask Roxtec for a distributor data feed and written permission to reuse images, descriptions, datasheets and certificates. If a feed arrives, a feed adapter replaces fetching and extraction; everything downstream (about 70% of Phase 1) is unchanged.

### 4.3 The crawl authorisation gate (enforced in code)

Each OEM row has `crawl_auth_status`: `PENDING` (default), `AUTHORISED` or `DENIED`. `JobService.create` enforces:

| Status | Allowed network jobs |
|---|---|
| `AUTHORISED` | All |
| `PENDING` | `DRY_RUN`, or runs limited to at most 20 families (`JobService.UNAUTHORISED_FAMILY_LIMIT`) |
| `DENIED` | None. Offline reprocessing jobs only |

Recording the decision is currently a database update (a dashboard screen for it is part of Step 14):

```sql
update oem set crawl_auth_status = 'AUTHORISED', crawl_auth_note = '<evidence>',
       crawl_auth_decided_by = '<name>', crawl_auth_decided_at = now()
 where code = 'ROXTEC';
```

---

## 5. Requirements (summary)

### 5.1 Functional

| ID | Requirement | Status |
|---|---|---|
| FR-01 | Create a job with OEM, locale, limit and unit, filters, existing-product policy and options | Built (API and seed-list form) |
| FR-02 | Job modes: DRY_RUN, FULL, SEED_LIST, REFRESH, REEXTRACT, REPARSE_DOCUMENTS, RENORMALIZE | Modes defined; SEED_LIST works end to end |
| FR-03 | Pause, resume, cancel jobs; retry dead items | Built |
| FR-04 | Discovery from sitemaps, categories, listing API and seed lists | Seed lists only (Step 5 pending) |
| FR-05 | Admission honours the limit exactly (families) or never cuts a family (SKUs) | Built |
| FR-06 | All network access through one polite fetcher | Built (HTTP mode) |
| FR-07 | Deterministic extraction over stored raw pages, with provenance | Built for Roxtec family pages |
| FR-08 | SKUs only from deterministic extraction | Built |
| FR-09 | Image and document download, deduplication, versioning | Links extracted; downloads pending (Step 7) |
| FR-10/11 | PDF parsing, document facts and projection | Pending (Step 8) |
| FR-12 | Bounded, grounded LLM extraction | Pending (Step 9) |
| FR-13 | Normalisation to typed canonical values | Pending (Step 10) |
| FR-14 | Live job progress, reasons, errors | Basic job page built |
| FR-15/16 | Product list and full-detail product page | Basic versions built |
| FR-17/18/19 | Reviewer edits, approval gate, approval snapshots | Schema ready; UI pending (Step 12) |
| FR-20/21 | Re-run semantics without losing human work; diffs | Partly (immutable revisions, unchanged-content skip) |
| FR-22 | Dictionary management | Schema ready; UI pending |
| FR-23 | Legal gate and canary before full crawls | Legal gate built; canary pending |
| FR-24 | JSON API for jobs | Built |

### 5.2 Non-functional

| ID | Category | Requirement |
|---|---|---|
| NFR-01 | Politeness | One request in flight per host; at least 3 s spacing (or robots.txt Crawl-delay); adaptive backoff; honest User-Agent |
| NFR-02 | Reliability | At-least-once execution with idempotent handlers; survives a crash at any point |
| NFR-03 | Resumability | All state in PostgreSQL; a restart resumes every job |
| NFR-04 | Reproducibility | Extraction re-runs from stored raw data with zero network requests |
| NFR-05 | Auditability | Provenance on every value; actor and time on every human action |
| NFR-06 | Data safety | Raw data and revisions immutable; human decisions never overwritten by machines |
| NFR-07 | Security | Internal network only; roles; CSRF; strict CSP; private datastores |
| NFR-08 | Performance | Progress query under 100 ms at 10,000 items; pages under 1 s at 500 families |
| NFR-09 | Cost control | LLM budgets and result caching |
| NFR-10 | Maintainability | One modular Spring Boot application; server-rendered UI; OEM logic isolated in adapters |

---

## 6. Architecture

### 6.1 Principles

1. **Pipeline, not agent.** Deterministic, resumable stages; the LLM is a bounded function inside some of them.
2. **Source adapter per OEM.** OEM-specific knowledge lives behind `OemAdapter`; a data-feed adapter would plug into the same slot.
3. **Canonical staging model, independent of Magento.** Magento mapping is a Phase 2 layer.
4. **Provenance on everything.** Every value records source type, locator and an excerpt.
5. **Raw is immutable; derived data is rebuildable.** Revisions never change once current.
6. **Human work lives in its own tables.** Machines never write to them.
7. **Single instance, modular monolith.**

### 6.2 Pipeline

```
 Dashboard / JSON API
        |
        v
 JobService --creates--> onboarding_job + seed work
        |
        v
 PostgreSQL work queue (work_item)  <-- leased with SKIP LOCKED + fencing token
        |
   +----+----------------+------------------+
   v                     v                  v
 NETWORK lane         LOCAL lane          LLM lane
 (1 req per host)     (CPU work)          (future)
   |                     |
 FETCH_PAGE  ------->  EXTRACT_FAMILY  --> CatalogWriter
 PageFetcher           OemAdapter          product_family, revision,
 (robots, pacing,      (pure function      variants, values, assets
  raw store)            over raw HTML)
        |
        v
 JobStateEvaluator: discovery done -> admission -> completion; stall detection
```

Planned stages not yet built: discovery handlers, asset download, PDF processing, LLM extraction, assembly, normalisation (see section 18).

### 6.3 Data layers

| Layer | Contents | Owned by | Mutability |
|---|---|---|---|
| Work | Jobs, work items, candidates, job events | Engine | Temporary |
| Raw | Fetched pages, original files | Fetcher, asset pipeline | Immutable |
| Extracted | Revisions, attribute values, asset links, document facts | Pipeline | New revision per change; never edited |
| Human | Overrides, curation, review events, flag dismissals, approval snapshots | Reviewers | People only; full history |

### 6.4 Technology stack

| Concern | Choice |
|---|---|
| Runtime | Java 21+, Spring Boot 4.1.1 (Jackson 3 as `tools.jackson`), virtual threads for I/O |
| Persistence | PostgreSQL 17 (pgvector image), Flyway migrations, `JdbcClient` |
| HTTP and parsing | `java.net.http.HttpClient`, Jsoup 1.21.1, crawler-commons 1.4 (robots.txt) |
| UI | Thymeleaf server-rendered pages, plain CSS |
| Storage | Content-addressed gzip blobs on disk (`FileSystemBlobStore`), MinIO/S3 planned |
| Testing | JUnit 5, Testcontainers 2 (PostgreSQL), Awaitility, JDK `HttpServer` for fetcher tests |

### 6.5 Where the LLM is and is not used (design)

| Allowed (bounded and verified) | Never |
|---|---|
| Structuring specification prose into label/value facts | Deciding what to crawl |
| Structuring complex PDF tables | SKUs, variants, identity |
| Classifying documents the rules could not | Certificate numbers and dates |
| Suggesting label-to-dictionary mappings (reviewer accepts) | Unit conversion, deduplication, orchestration |
| | Approvals; overriding deterministic values or reviewer edits |

Every LLM fact must carry a verbatim quote that a deterministic verifier checks against the source. Not yet built (Step 9).

---

## 7. Codebase tour

### 7.1 Package map

| Package | Responsibility | Key classes |
|---|---|---|
| `engine` | Generic, resumable work queue: leasing, fencing, lanes, retries, lifecycle | `WorkItemRepository`, `TaskRunner`, `LaneDispatcher`, `RetryPolicy`, `EngineLifecycle`, `TaskType`, `TaskHandler`, `HostGate` |
| `job` | Jobs, candidates, admission, job state, progress, JSON API | `JobService`, `CandidateAdmissionService`, `JobStateEvaluator`, `JobProgressQuery`, `JobController` |
| `oem` | OEM records and the crawl authorisation status | `Oem`, `OemRepository` |
| `crawl` | Polite fetching: scope, robots.txt, pacing, raw storage | `PageFetcher`, `PoliteHostGate`, `RobotsPolicy`, `UrlNormalizer`, `FetchProperties` |
| `storage` | Content-addressed blob storage | `BlobStore`, `FileSystemBlobStore` |
| `adapter` | OEM-specific extraction behind one interface | `OemAdapter`, `ExtractedFamily`, `AdapterRegistry`, `roxtec.RoxtecAdapter` |
| `pipeline` | Task handlers that wire fetching, extraction and the catalogue | `FetchPageHandler`, `ExtractFamilyHandler` |
| `catalog` | Writing revisions and reading product views | `CatalogWriter`, `CatalogQueries` |
| `web` | Browser pages and API error mapping | `DashboardController`, `ProductController`, `ApiExceptionHandler` |

### 7.2 Life of a seed-list job (end to end)

1. The operator submits URLs on `/`. `DashboardController` calls `JobService.create` with mode `SEED_LIST`.
2. `JobService` checks the OEM, the legal gate and the one-active-job rule, inserts the job (`DISCOVERING`) and one `job_candidate` per URL (normalised, ordered).
3. `JobStateEvaluator` (every 10 s) sees no discovery work outstanding and calls `CandidateAdmissionService.startAdmission`: the job becomes `RUNNING` and up to `limit` candidates are admitted, each enqueuing a `FETCH_PAGE` item.
4. The NETWORK `LaneDispatcher` leases a `FETCH_PAGE` item only when its host is ready (`PoliteHostGate.hostsNotReady`), and never two for the same host at once.
5. `FetchPageHandler` calls `PageFetcher.fetch`: scope check, robots.txt, freshness reuse, pacing, HTTP GET, classification, raw body stored, `fetched_resource` row inserted.
6. `RoxtecAdapter.check` confirms a product family page. If not, the candidate is rejected and its slot goes to the next candidate. If so, an `EXTRACT_FAMILY` item is enqueued with the resource id.
7. `ExtractFamilyHandler` reads the stored page (no network), runs `RoxtecAdapter.extract`, and in the completion transaction `CatalogWriter.write` creates a new revision (or skips if content is unchanged) and `CandidateAdmissionService.complete` closes the candidate.
8. When nothing is active and nothing more can be admitted, the evaluator marks the job `COMPLETED` (or `COMPLETED_WITH_ERRORS` if items died).

---

## 8. Job engine (deep dive)

### 8.1 Model

A **job** (`onboarding_job`) is a request ("onboard these products"). Its work is a set of **work items** (`work_item`), each of a `TaskType` with a fixed lane, attempt budget and lease length:

| Task type | Lane | Max attempts | Lease | Status |
|---|---|---|---|---|
| DISCOVER, FETCH_SITEMAP, FETCH_CATEGORY, FETCH_LISTING | LOCAL / NETWORK | 3 / 5 | 1 to 2 min | Types defined; handlers pending (Step 5) |
| FETCH_PAGE | NETWORK | 5 | 2 min | Built |
| EXTRACT_FAMILY | LOCAL | 2 | 5 min | Built |
| DOWNLOAD_ASSET | NETWORK | 5 | 10 min | Pending (Step 7) |
| PARSE_DOCUMENT | LOCAL | 2 | 10 min | Pending (Step 8) |
| LLM_EXTRACT | LLM | 3 | 3 min | Pending (Step 9) |
| ASSEMBLE_FAMILY, NORMALIZE | LOCAL | 2 | 2 min | Pending (Steps 8, 10) |
| PLAN_REPROCESS | LOCAL | 2 | 5 min | Seed item for offline modes; handler pending |

Dispatchers lease only task types that have a registered handler, so work for an unbuilt stage waits in the queue instead of failing.

### 8.2 Guarantees and how they are achieved

| Guarantee | Mechanism |
|---|---|
| No item processed by two workers at once | `FOR UPDATE SKIP LOCKED` lease in one short transaction |
| A slow worker cannot overwrite a newer result | Every lease gets a new `lease_token` (fencing token); completion updates only where status is LEASED and the token matches |
| Results and "done" commit together | Handler work runs outside any transaction; `TaskRunner` commits `onCommit` writes, marks DONE and enqueues children in one transaction |
| Crashes lose nothing | Expired leases are returned to the queue by `LeaseReaper` (every 30 s); the attempt counted at lease time stops infinite loops |
| Enqueue is idempotent | Unique `(job_id, task_type, dedupe_key)`; `ON CONFLICT DO NOTHING` |
| No work added to finished jobs | Enqueue inserts only if the job is still active |
| Clock skew is irrelevant | All lease and retry times use the database clock (`now()`) |

Delivery is **at-least-once**; handlers must be idempotent (natural keys and dedupe keys make repeats harmless).

### 8.3 Lanes

| Lane | Threads | Concurrency (default) | Why |
|---|---|---|---|
| NETWORK | Virtual threads | 4 overall, 1 per host | Blocking I/O; politeness per host |
| LOCAL | Platform thread pool | 4 | CPU-bound parsing |
| LLM | Virtual threads | 3 | Provider rate limits (future) |

### 8.4 Retry classes

| Error class | Example | Behaviour | Attempt consumed |
|---|---|---|---|
| TRANSIENT | Timeout, 5xx | Exponential backoff with jitter (30 s, 2 min, 8 min, capped at 30 min) | Yes |
| RATE_LIMITED | HTTP 429 with Retry-After | Rescheduled after the delay; host spacing doubled | No |
| HOST_BLOCKED | Challenge page, 403 | Host marked BLOCKED until a person resumes it; item rescheduled | No |
| PERMANENT | 404, not a product | DEAD immediately (or candidate rejected) | n/a |
| SKIPPED | robots.txt disallow, out of scope | SKIPPED | n/a |
| BUG | Unexpected exception | At most 2 attempts | Yes |
| LEASE_EXPIRED | Worker died | Returned to the queue by the reaper | Yes |

### 8.5 Admission

- **FAMILY limit:** exactly N valid candidates are admitted; a candidate rejected at fetch time (not a product, missing, disallowed) releases its slot and the next one is admitted.
- **SKU limit:** families are admitted in waves of 10 until the extracted SKU count reaches the limit; a family is never cut, so the result may overshoot.
- **Order:** deterministic (`order_key`, then id), so re-runs select the same set.
- **Concurrency:** all admission decisions for a job are serialised by a PostgreSQL advisory lock (`pg_advisory_xact_lock`), which the state evaluator also takes before reading the job's work. This closed a real race found in testing (a job could be marked complete while a replacement candidate was being admitted).

### 8.6 Job state evaluator

Runs every 10 s for jobs in `DISCOVERING` or `RUNNING`:

1. Discovery finished and the mode admits candidates: start admission (`RUNNING`).
2. Running: top up admission.
3. Nothing active and nothing admitted: complete (`COMPLETED` or `COMPLETED_WITH_ERRORS`).
4. Work due but nothing completed for 15 minutes: a `STALLED` event (at most once per window).

It locks the job row with `FOR NO KEY UPDATE`, not `FOR UPDATE`, so foreign-key inserts of new work items are never blocked (avoids a deadlock with admission).

### 8.7 Job lifecycle and rules

- One active job per OEM (partial unique index `onboarding_job_one_active_per_oem`).
- Pause stops new leases for that job; in-flight work finishes. Resume restores the previous status.
- Cancel drops waiting items; in-flight items finish and their results are kept, their children dropped.
- Retry dead re-queues dead items with a fresh attempt budget, reopening a job that finished with errors.

---

## 9. Polite fetcher

`PageFetcher` is the only class that talks to OEM websites.

| Step | Behaviour |
|---|---|
| Normalise | Lower-case scheme and host, default port and fragment removed (`UrlNormalizer`) |
| Scope | Host must be in the OEM's allowlist (currently the OEM's own host); otherwise SKIPPED |
| robots.txt | Fetched once per host (paced like any request), cached 24 h in `crawl_host`; 4xx means allow all, 5xx or network failure means disallow all for 10 minutes (RFC 9309) |
| Freshness | A successful fetch of the same URL within 7 days is reused without contacting the site |
| Pacing | `PoliteHostGate.acquire`: fixed spacing per host (3 s default, ±20% jitter, or the Crawl-delay if larger). Spacing doubles on 429 (capped at 60 s) and recovers after 50 successes |
| Request | Honest User-Agent `OEMIntelBot/0.1` (+ optional contact), `Accept-Encoding: gzip` decoded manually (java.net.http does not decompress) |
| Redirects | Followed manually, at most 5 hops, scope and robots.txt re-checked on every hop, chain recorded |
| Size | Body capped at 10 MB while streaming; Content-Length never trusted |
| Store | Body stored as a content-addressed gzip blob; `fetched_resource` row inserted for every fetch, success or failure |

**Response classification:**

| Response | Classification | Engine outcome |
|---|---|---|
| 2xx | OK | Success |
| 2xx/403/503 with challenge markers | CHALLENGE | HOST_BLOCKED |
| 404, 410 | NOT_FOUND | PERMANENT |
| 429 | RATE_LIMITED | RATE_LIMITED (Retry-After honoured) |
| 403 | BLOCKED | HOST_BLOCKED |
| 5xx | SERVER_ERROR | TRANSIENT |
| Timeout, connection error | n/a | TRANSIENT |

Challenge detection is deliberately specific (Cloudflare challenge markers) because ordinary product pages may embed CAPTCHA forms. There are no proxies, no disguised User-Agent and no CAPTCHA solving: a block means stop and contact the OEM.

---

## 10. The Roxtec adapter

### 10.1 Site reconnaissance (2 October 2026)

| Finding | Evidence |
|---|---|
| robots.txt allows product pages | Disallows `/api/datalayer`, search and filter URLs, `/account/*`, `/setmarket/`, and CAD files (`*.dxf`, `*.stp`) |
| Sitemap index with 32 locale sitemaps | `https://www.roxtec.com/sitemap.index.xml` (locales as path prefixes: `/en/`, `/uk/`, `/us/` ...) |
| English sitemap | 2,019 URLs; 1,136 under `/en/products/` |
| Product hierarchy | `/en/products/system-components/<category>/<family>/<article>/` |
| Catalogue size (English) | 180 family pages, 743 article pages; categories: rectangular frames, round frames/seals, sealing components, accessories |
| Rendering | Server-rendered HTML; served via Cloudflare to our bot without a challenge |
| Structured data | Each family page has a schema.org `ProductGroup` in JSON-LD with `hasVariant` (SKU, name, weight, size, image per article) and a `BreadcrumbList` |
| Spec tables | Per-material tables with a `<caption>` (for example "Galvanized mild steel") and an "Art. No" column |
| Documents | Drawing PDFs and installation instructions as links; certificate PDFs inside `<script type="text/template">` modal templates; hosted on `cdn.content-publisher.roxtec.com` |
| Article numbers | Mixed formats: numeric (`114891`) and alphanumeric (`5GH0000008110`, `EXG006000000215`) |

### 10.2 What the adapter extracts (version `roxtec-0.1`)

The adapter is a pure function over stored HTML (no network), in this order:

1. **Validation:** a page without a `ProductGroup` is not a family page (candidate rejected with that reason).
2. **Identity:** family key (`productGroupID`), name, description, category, breadcrumb, canonical URL.
3. **Articles from JSON-LD:** SKU, label (`mpn`), URL, weight (unit from `unitCode`), every `additionalProperty`, variant images.
4. **Spec tables:** every table with an "Art. No" column becomes per-article values (one per column), with the caption recorded as "Table group". Locator (`table[n] "caption" row r column "header"`) and the full row text are kept as provenance. Tables without an article column (the certificates matrix) are reported as warnings, not guessed at.
5. **Documents:** PDF links on the page and inside modal templates, typed by URL path (CERTIFICATE, DRAWING, DOCUMENT); certificates titled with the modal's approval name. CAD formats are never collected.
6. **Images:** family image (MAIN_IMAGE), gallery and per-variant images, deduplicated.

### 10.3 Provenance and corroboration

Each value records its source (`JSON_LD` or `HTML_SELECTOR`), a locator and an excerpt. When two sources give the same label and value (for example weight in JSON-LD and in the table), the value is stored once and the second source recorded as corroboration (shown underlined in the UI). Different values for the same label are both kept so a reviewer sees the conflict.

### 10.4 Results of the first run (job 2)

| Family | SKUs | Values | Images | Documents |
|---|---|---|---|---|
| KOS accessories | 3 | 5 | 2 | 0 |
| Roxtec CF 16 BG frame | 1 | 10 | 1 | 6 |
| Roxtec CF 8/32 BG frame | 2 | 17 | 1 | 6 |
| Roxtec C LH frame | 5 | 38 | 1 | 2 |
| Roxtec CM ES module with Multidiameter | 4 | 27 | 1 | 2 |
| Roxtec CM PE module with Multidiameter | 5 | 33 | 1 | 2 |
| Roxtec Compression unit tools | 3 | 6 | 2 | 5 |
| Roxtec CRL frame | 0 (flagged) | 3 | 1 | 3 |
| Roxtec C RS SLO seal | 4 | 23 | 1 | 6 |
| Roxtec C Stayplate | 2 | 13 | 1 | 1 |
| Roxtec Inspection tools | 6 | 9 | 2 | 7 |
| Roxtec Knock-out sleeve | 8 | 27 | 7 | 7 |
| Roxtec LabSeal C LTX frame | 2 | 17 | 1 | 5 |
| Roxtec RM UG solid compensation module | 4 | 27 | 1 | 1 |
| Roxtec R W Ex kit | 10 | 53 | 6 | 20 |

The CRL frame page genuinely lists no articles (no `hasVariant`, no article table, no article links), so it was correctly flagged `MISSING_SKU` rather than filled with guesses.

### 10.5 Adding another OEM

1. Implement `OemAdapter` (`key`, `extractorVersion`, `check`, `extract`) in a new package under `adapter`.
2. Insert the OEM row (`oem.adapter_key` must match `key()`), with `crawl_auth_status = 'PENDING'`.
3. Save representative pages as fixtures and write a golden test like `RoxtecAdapterTests` before any live run.
4. Raise `extractorVersion` whenever extraction logic changes.

---

## 11. Data model

Schema conventions: bigint identity keys, unique constraints on natural keys, text enums with check constraints, `timestamptz`, no soft deletes. Migrations: `V1` configuration and work layer, `V2` raw layer, `V3` dictionary and extracted layer, `V4` human layer.

### 11.1 Configuration and work

| Table | Purpose | Key rules |
|---|---|---|
| `oem` | Manufacturers, adapter key, crawl authorisation | `code` unique |
| `crawl_host` | Per-host robots.txt cache, delay, block state | `host` unique |
| `onboarding_job` | Jobs | One active job per OEM (partial unique index) |
| `work_item` | Queue | Unique `(job_id, task_type, dedupe_key)`; lease token for fencing |
| `job_candidate` | Product URLs to admit | Unique `(job_id, normalized_url)` |
| `job_event` | Human-readable job timeline | |
| `app_user` | Users and roles (not yet used) | `username` unique |

### 11.2 Raw

| Table | Purpose | Key rules |
|---|---|---|
| `fetched_resource` | One row per fetch (never updated) | Indexed by URL and time |
| `asset` | Unique file content | `sha256` unique |
| `asset_source` | URL where a file was found | Unique `(oem_id, normalized_url)` |
| `asset_source_history` | Versions behind one URL | |
| `document_analysis` | PDF parse results (future) | Unique `(asset_id, parser_version)` |

### 11.3 Dictionary and extracted

| Table | Purpose | Key rules |
|---|---|---|
| `product_family` | The unit of review | Unique `(oem_id, locale, family_key)`; `version` for optimistic locking |
| `product_variant` | One SKU | Unique `(oem_id, locale, sku_normalized)`; never deleted |
| `extraction_revision` | Immutable snapshot of an extraction | Unique `(family_id, revision_number)`; one CURRENT per family |
| `attribute_value` | One extracted value with provenance | Raw label and value kept; typed columns for normalisation |
| `product_asset_link`, `product_relation`, `family_category` | Assets, relations, category paths per revision | |
| `attribute_definition`, `attribute_option`, `attribute_label_alias`, `unit_definition`, `unit_alias`, `product_type` | Attribute dictionary (system attributes `name`, `description`, `short_description` seeded) | |
| `document_fact`, `unstructured_section`, `llm_call`, `revision_change` | Future stages | |

### 11.4 Human

| Table | Purpose | Key rules |
|---|---|---|
| `field_override` | Reviewer values: SET, CLEAR, CHOOSE, CONFIRM, NOT_APPLICABLE | Keyed by family, variant and attribute, never by value row id; one active per key |
| `asset_curation`, `document_fact_assignment` | Reviewer decisions on assets and document facts | |
| `product_flag` | Issues for reviewers | Unique `(family_id, flag_type, target_key)`; condition fingerprint for dismissals |
| `review_event` | Audit trail | |
| `approval_snapshot` | Immutable record of what was approved | A database trigger refuses update and delete |
| `product_type_attribute`, `category_product_type_map`, `label_mapping_suggestion`, `saved_view` | Required attributes, mappings, UI preferences | |

### 11.5 Core rules

- **Effective value** = active reviewer override, otherwise the extracted value in the CURRENT revision (variant level wins over family level). Computed, never stored. Overrides are not yet editable in the UI, so pages currently show extracted values.
- **Unchanged content:** if a new extraction's content fingerprint equals the current revision's, no revision is created and reviewers are not disturbed.
- **Approved families** move to `NEEDS_REREVIEW` when a new revision is created.
- **Products outlive jobs:** deleting a job never deletes catalogue data.

### 11.6 Review states

| From | Event | To |
|---|---|---|
| (none) | First revision | NEW |
| NEW or NEEDS_REREVIEW | First edit or "start review" | IN_REVIEW |
| IN_REVIEW or NEEDS_REREVIEW | Approve (gate passes, data not stale) | APPROVED (snapshot written) |
| Any open status | Reject with reason | REJECTED (sticky) |
| APPROVED | Material change in a new revision | NEEDS_REREVIEW |

Only the NEW and NEEDS_REREVIEW transitions are implemented today; the review workflow is Step 12.

---

## 12. User interface and API

### 12.1 Browser pages

| Route | Page |
|---|---|
| `GET /` | Recent jobs and the seed-list form (pre-filled with 15 Roxtec families) |
| `POST /jobs` | Creates a SEED_LIST job from the form |
| `GET /jobs/{id}` | Job status, pipeline counts, timeline, candidates, problems (auto-refresh while active) |
| `POST /jobs/{id}/cancel` | Cancels a job |
| `GET /products` | All product families with thumbnail, category, SKU, image and document counts, flags |
| `GET /products/{id}` | Family values, article matrix with provenance tooltips, images, documents, flags, source |

Images are displayed directly from Roxtec's CDN for review; nothing is downloaded or republished yet.

### 12.2 JSON API

| Method and path | Purpose |
|---|---|
| `POST /api/jobs` | Create a job |
| `GET /api/jobs/{id}` | Job plus progress (items by type and status, candidates by status, recent events) |
| `POST /api/jobs/{id}/pause?reason=...` | Pause |
| `POST /api/jobs/{id}/resume` | Resume |
| `POST /api/jobs/{id}/cancel` | Cancel |
| `POST /api/jobs/{id}/retry-dead` | Re-queue dead items |
| `GET /actuator/health` | Health |

Errors are RFC 9457 problem responses (403 legal gate, 409 conflicts, 400 validation, 404 not found).

```bash
curl -X POST http://localhost:8090/api/jobs -H "Content-Type: application/json" \
  -d '{"oemCode":"ROXTEC","mode":"SEED_LIST","limitValue":2,
       "seedUrls":["https://www.roxtec.com/en/products/system-components/rectangular-frames/roxtec-gh-frame/"]}'
```

---

## 13. Configuration reference

All application settings live in `src/main/resources/application.yml`.

| Property | Default | Meaning |
|---|---|---|
| `server.port` / `server.address` | `8090` / `127.0.0.1` | HTTP port and bind address |
| `oemintel.engine.enabled` | `true` | Run the engine (disable to run only the UI or in repository tests) |
| `oemintel.engine.poll-interval` | `1s` | How often each lane looks for work |
| `oemintel.engine.reaper-interval` | `30s` | Expired-lease check |
| `oemintel.engine.evaluator-interval` | `10s` | Job state evaluation |
| `oemintel.engine.stall-after` | `15m` | Stall detection window |
| `oemintel.engine.shutdown-grace` | `20s` | Time given to in-flight work on shutdown |
| `oemintel.engine.lanes.*-concurrency` | network 4, local 4, llm 3 | Lane capacity |
| `oemintel.engine.retry.base-delay` / `max-delay` / `jitter` / `host-blocked-delay` | `30s` / `30m` / `0.2` / `15m` | Retry backoff |
| `oemintel.fetch.user-agent` | `OEMIntelBot/0.1` | Crawler identity |
| `oemintel.fetch.contact` | empty | Optional contact appended to the User-Agent |
| `oemintel.fetch.default-delay` | `3s` | Minimum spacing between requests to one host |
| `oemintel.fetch.jitter`, `max-delay` | `0.2`, `60s` | Spacing jitter and backoff cap |
| `oemintel.fetch.connect-timeout`, `request-timeout` | `10s`, `30s` | Timeouts |
| `oemintel.fetch.max-page-bytes`, `max-redirects` | `10000000`, `5` | Safety caps |
| `oemintel.fetch.freshness`, `robots-ttl` | `7d`, `24h` | Reuse window and robots.txt cache |
| `oemintel.pipeline.enabled` | `true` | Register the real fetch/extract handlers (engine tests turn it off) |
| `oemintel.storage.root` | `data/blobs` | Blob storage directory |

---

## 14. Testing

### 14.1 Suites

| Test class | Type | What it proves |
|---|---|---|
| `OemintelApplicationTests` | Integration | Context starts, all migrations apply, Roxtec seeded as PENDING |
| `SchemaConstraintsTests` | Integration | Unique SKU, one CURRENT revision, one active override, immutable approval snapshot, relation target rule |
| `WorkItemRepositoryTests` | Integration | Idempotent enqueue, fencing, refund on rate limit, reaper kills exhausted items, no early retries, one request per host, paused jobs not leased |
| `JobServiceTests` | Integration | Legal gate (PENDING, AUTHORISED, DENIED), one active job, seed list ordering and validation, pause/resume/cancel |
| `EngineIntegrationTests` | Integration (engine running) | 2,000-item chaos run loses nothing and commits nothing twice; admission fills the limit exactly and replaces rejected candidates |
| `PageFetcherTests` | Integration (local HTTP server) | robots.txt disallow never requested, spacing held, fresh copy reused, response classes, redirects recorded |
| `RoxtecAdapterTests` | Unit (golden) | Against a saved Roxtec page: identity, 97 articles with no duplicates, table values with provenance, images and documents, no CAD |

All suites use a real PostgreSQL in Docker because the queue depends on PostgreSQL semantics (`SKIP LOCKED`, advisory locks, partial indexes).

### 14.2 Status and known issue

- The first 23 tests passed on 30 September 2026; the 10 fetcher and adapter tests passed on 2 October 2026. The full suite has not been re-run since the thin-slice changes.
- **Known issue:** on the development machine (Windows, Docker Desktop) a full `mvnw test` takes more than 10 minutes of wall time although the tests themselves take about 30 seconds. Likely container start-up overhead; not yet investigated.

### 14.3 Test data

`src/test/resources/fixtures/roxtec/roxtec-gh-frame.html` is a copy of a public Roxtec page fetched on 2 October 2026, kept for internal testing only. Do not redistribute it.

---

## 15. Operations

| Topic | Current practice |
|---|---|
| Runtime | Single instance; Docker Compose for PostgreSQL; app via Maven |
| Logs | Console (and `target/app.log` when started in the background). Job and work item ids are set in the logging context (MDC) but not yet printed by the default log pattern |
| Health | `GET /actuator/health` |
| Data | PostgreSQL volume `oemintel_postgres-data`; raw pages under `data/blobs` (git-ignored) |
| Memory | The dev machine runs low on memory with several stacks running; start the app with `-Xmx512m` |
| Blocked host | Status BLOCKED in `crawl_host`; do not work around it; contact the OEM; resume with `update crawl_host set status = 'OK', block_reason = null where host = '...'` |
| Backups | Not yet in place (planned: nightly pg_dump plus blob mirror, with a restore drill) |

---

## 16. Security and compliance

### 16.1 Current posture

| Area | State |
|---|---|
| Exposure | App binds to 127.0.0.1; PostgreSQL bound to 127.0.0.1 |
| Authentication | None yet (Step 11 adds form login with ADMIN and REVIEWER roles, CSRF, strict CSP) |
| Third-party content | Templates escape all extracted text; OEM HTML is never rendered as live HTML |
| Secrets | Environment variables; `.env` git-ignored |
| Crawl compliance | robots.txt, honest User-Agent, scope allowlist, pacing, stop on block, legal gate |

### 16.2 Incident record

On 2 October 2026, during reconnaissance, one robots.txt request to `www.roxtec.com` was sent with the project owner's email address in the User-Agent header without their prior agreement. All subsequent requests identify only as `OEMIntelBot/0.1`. A contact is now opt-in via `OEMINTEL_CRAWL_CONTACT`.

---

## 17. Architecture decisions

| # | Decision | Chosen | Rejected alternatives |
|---|---|---|---|
| AD-01 | Overall approach | Deterministic pipeline with a bounded LLM | Autonomous browsing agent |
| AD-02 | Queue | Own `work_item` table with SKIP LOCKED | JobRunr, RabbitMQ/Kafka, Spring Batch, Temporal |
| AD-03 | Delivery | At-least-once, idempotent handlers, fencing tokens | Distributed transactions |
| AD-04 | Lease model | Short lease transaction; work outside transactions; token-checked completion | Holding row locks during work |
| AD-05 | Lanes | NETWORK, LOCAL, LLM with separate concurrency | One shared pool |
| AD-06 | Concurrency | One active job per OEM; single instance | Parallel jobs with per-family locks |
| AD-07 | Pacing | Fixed spacing per host with jitter and backoff | Token buckets (allow bursts) |
| AD-08 | Gate before lease | Lease network items only when the host is ready | Lease then wait |
| AD-09 | User-Agent | Honest bot identity | Posing as a browser |
| AD-13 | Discovery | Hybrid sitemap plus categories, complete before admission | Generic spider |
| AD-14 | Extraction | Pure function over stored raw pages; Java adapter code, versioned | Fetching inside the extractor; generic selector language |
| AD-15 | Attribute storage | Attribute-value rows with typed columns and provenance | JSONB per product; wide table |
| AD-16 | Override keying | Family, variant and attribute key | Lock flags on value rows |
| AD-17 | Review granularity | Family-level approval, per-variant exclusion | Per-SKU approval |
| AD-18 | File storage | Content-addressed blobs; originals never modified | Filename keys; Postgres bytea |
| AD-21 | Assembly | DRAFT, fan-in on assets and LLM, then CURRENT | Normalising straight after extraction |
| AD-23 | LLM control | Grounding verifier, critical-value confirmation, cache, pinned model ids, budgets | Trusting structured output |
| AD-26 | UI | Server-rendered Thymeleaf (htmx planned) | React SPA, Vaadin, Magento admin |
| AD-27 | Revisions | Immutable; derived revisions for re-normalisation | In-place updates |
| AD-29 | Publishing basis | Immutable approval snapshot | Live data at publish time |
| AD-30 | Legal gate | OEM crawl authorisation enforced in `JobService` | Checklist only |

**Implementation deviations (deliberate, temporary):**

| Deviation | Reason | To be resolved in |
|---|---|---|
| Blobs on local disk instead of MinIO | `minio/minio` image could not be pulled from Docker Hub | Step 7 |
| Revisions become CURRENT immediately (no DRAFT or assembly) | Thin slice before documents and normalisation exist | Steps 8 and 10 |
| Values stored raw (no typed normalisation) | Normaliser not built | Step 10 |
| Images shown from the OEM CDN; documents not downloaded | Asset pipeline not built | Step 7 |
| JPA not used (JdbcClient only) | Not needed until reviewer editing | Step 12 |
| Simple pages without htmx or login | Thin slice | Step 11 |

---

## 18. Implementation status against the roadmap

| Step | Title | Status |
|---|---|---|
| 0 | Site reconnaissance and permission | Partly done: robots.txt, sitemaps, page structure analysed (section 10.1). Terms of use review and Roxtec permission request still open |
| 1 | Project skeleton | Done |
| 2 | Domain model and schema | Done (V1 to V4) |
| 3 | Job engine and task queue | Done |
| 4 | Polite fetcher | HTTP mode done; browser mode not needed so far (pages are server-rendered) |
| 5 | Discovery | Seed lists only; sitemap and category discovery pending |
| 6 | Deterministic extraction | Roxtec family pages done; golden set of 10 hand-recorded products still to be recorded |
| 7 | Asset pipeline | Pending (links extracted) |
| 8 | Document processing | Pending |
| 9 | LLM with grounding | Pending (provider and data policy undecided) |
| 10 | Normalisation | Pending |
| 11 | Dashboard: jobs | Basic pages; security and htmx pending |
| 12 | Dashboard: review | Basic list and detail; editing and approval pending |
| 13 | Re-run semantics and diffs | Immutable revisions and unchanged-content skip done; rest pending |
| 14 | Hardening and full run | Pending |

**Effort:** the realistic estimate for Phase 1 is 10 to 12 weeks for one developer (plus 1 to 2 weeks if browser rendering becomes necessary). Deferrable features if time is short: merging, bulk approval, saved views, export, LLM label suggestions.

---

## 19. Known issues and technical debt

| Issue | Impact | Suggested fix |
|---|---|---|
| Full test suite wall time over 10 minutes locally | Slow feedback | Investigate Testcontainers start-up; consider container reuse |
| `options` in the job API is returned as a JSON string, not an object | Cosmetic API wart | Return parsed JSON |
| Candidates of a cancelled job stay `ADMITTED` | Misleading counts on cancelled jobs | Close candidates on cancel |
| A candidate whose fetch dies after retries stays `ADMITTED` | Slot not released | Release slot on DEAD fetch items |
| Domain errors on HTML pages render as JSON problem responses | Unfriendly 404 page | HTML error page for browser routes |
| No authentication | App must stay on localhost | Step 11 |
| No backups | Data loss risk | Step 14 |
| Robots cache refresh holds a global lock during the fetch | Slows multi-host crawls | Per-host locking (only one host today) |

---

## 20. Next steps

1. Record the legal decision (Roxtec terms of use; send the feed and permission request).
2. Record the golden sample set (10 families, values written down from the site before looking at output).
3. Step 5: sitemap discovery so the full English catalogue (180 families) can be admitted without hand-picked URLs.
4. Step 7: download images and PDFs (choose the object store: MinIO alternative or S3).
5. Steps 11 and 12: login, reviewer editing, approval gate, approval snapshots.
6. Step 10: attribute dictionary and normalisation (needs canonical units, critical attributes, product types, ideally the Magento attribute export).

---

## 21. Open decisions

| # | Decision | Default used | Blocks |
|---|---|---|---|
| 1 | Legal: terms reviewed, permission or feed requested | Full runs refused (enforced) | Full run |
| 2 | Golden sample set | None | Accuracy acceptance |
| 3 | LLM provider, data policy, budget | Deterministic only | Step 9 |
| 4 | Reviewers and access model | Localhost only, no login | Step 11 |
| 5 | Canonical units, critical attributes, product types | mm, bar, °C, kg; proposed critical list | Step 10 |
| 6 | Object store for assets | Local disk | Step 7 |
| 7 | OCR | Off | Step 8 |
| 8 | Crawler contact in User-Agent | None | Nothing (recommended before scale) |
| 9 | Server, backups, alert channel | Not chosen | Step 14 |

---

## 22. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Terms or copyright forbid crawling or reuse | Medium | High | Permission request; enforced legal gate; usage-rights status |
| R2 | Wrong or stale specs or certificates published | Medium | High | Provenance, conflicts, mandatory review, confirmation of critical values |
| R3 | Product model mismatch | Low (confirmed by recon) | High | Family and variant model matches Roxtec's structure |
| R4 | Bot protection or IP block | Low to medium | High | Politeness, honest identity, stop on block |
| R5 | Reviewer capacity (about 40 hours per 500 families) | High | Medium | Efficient review UI, staged ramp-up |
| R6 | Site redesign breaks extraction | Medium | Medium | Golden tests, canary pre-flight, versioned adapters |
| R7 | LLM hallucination | Medium | Medium | Grounding verifier; deterministic values preferred |
| R8 | Reviewer edits lost on re-run | Low (by design) | High | Human-layer tables; preservation tests |
| R9 | Data loss | Low | High | Backups and restore drill (Step 14) |

---

## 23. Glossary

| Term | Meaning |
|---|---|
| Family | A product as reviewed (for example "Roxtec GH frame"); holds one or more variants |
| Variant / SKU / article | One orderable OEM article number |
| Candidate | A product URL waiting to be admitted to a job |
| Admission | Selecting candidates within the job's limit |
| Work item | One unit of queued work (fetch a page, extract a family ...) |
| Lease / fencing token | A time-limited claim on a work item, with a token that stops stale workers committing |
| Revision | An immutable extracted snapshot of a family |
| Provenance | Source type, locator and excerpt behind a value |
| Corroboration | A second source agreeing with a stored value |
| Effective value | Reviewer override if present, otherwise the current extracted value |
| Crawl authorisation | The recorded legal decision for crawling an OEM |
| Golden set | Hand-recorded expected values used to measure extraction accuracy |
