# Next iteration: quick fixes + stage 7 (Notion export)

Written 2026-10-15 at `a6dfaee` (`main`, after PR #1 and #2). Read this first, then `docs/meeting-notes-handoff.md`
(rules, traps) and README ("Meetings", "Cloud data pipeline"). Answer the user in Russian; every file in English.

## 0. State of the project
- `main` holds everything: video analysis + live camera, meetings (stages 1-6), cloud data pipeline.
- AWS: main account **906655020884**, profile `main` (IAM user `visiontrack-admin`), region eu-west-1, Terraform
  workspace `main`, bucket `visiontrack-datalake-906655020884-eu-west-1`. Profile `MyAWS`/`default` is a
  DIFFERENT account (175488706563): never deploy there.
- App upload identity: profile `visiontrack-uploader` (role that may only `PutObject` under `raw/`).
- Tests at `a6dfaee`: 88 Java (`mvn clean package`, needs the GPU), 32 pytest in `infra/lambda`.
- No `gh` CLI: open PRs with a prefilled compare link (`.../compare/main...<branch>?expand=1&title=..&body=..`).
- Terraform binary: `~/AppData/Local/Microsoft/WinGet/Packages/Hashicorp.Terraform_*/terraform.exe` (not on PATH
  in old shells).

## 1. Working rules (unchanged, keep them)
- One branch per iteration from `main` (proposed: `iteration-fixes-notion`); small commits; no attribution lines
  in commits or PRs; check staged files for Cyrillic and control characters before every commit.
- Plan per file → code → `mvn clean package` (always `clean`: stale classes from other branches end up in the jar)
  → acceptance in the running app on port 18080 → commit → push → PR link.
- GPU only, no CPU fallbacks. Models strictly sequential in VRAM (`ObjectDetectorProvider.unloadIfUnused`).
- Never rebuild `target/*.jar` while the user runs it; never run `terraform apply/destroy` or any changing AWS call
  without showing the plan + cost and getting an explicit OK.
- Delete test meetings and test data (local and in S3) after acceptance.

## 2. Part A: quick fixes (one or two commits)

| # | Task | Done when |
|---|---|---|
| A1 | **JVM crash on shutdown**: `OrtSession.close()` in `onnxruntime.dll` from the Spring shutdown hook (`ObjectDetectorProvider.close`). Skip closing the CUDA session during JVM shutdown (let the process exit); keep `unloadIfUnused` closing it at runtime. | 5 start/stop cycles (with the detector loaded) leave no new `hs_err_pid*.log`; then delete the 15 old ones in the project root (gitignored, ask first). |
| A2 | **Default profile reachable from the LAN**: add `server.address: 127.0.0.1` to `application.yml` (desktop already has it). | `netstat` shows `127.0.0.1:8080`, not `0.0.0.0`. |
| A3 | **`-CpuOnly` in `packaging/build-desktop.ps1`**: remove the switch and the message that suggests it. | Script has no CPU path. |
| A4 | **`packaging/README.txt`**: "first start takes 10-30 seconds while the model loads" is wrong (YOLO loads on first use). Also mention NVIDIA driver 528+. | Text matches the app. |
| A5 | **Dockerfile** (CPU, "not maintained" in README): ask the user whether to delete it and `.dockerignore`. | Decision applied. |
| A6 | **Cloud export in the desktop build**: `VisionTrack.exe` runs with profile `desktop` only. Document (or support) `--spring.profiles.active=desktop,aws` plus `AWS_PROFILE` / `VISIONTRACK_CLOUD_BUCKET`; until the Settings tab (stage 8) there is no UI for it. | README "Enable the export" covers the desktop app. |

## 3. Part B: stage 7, Notion export of meeting notes

Goal: a finished meeting (`READY`, with `notes.json`) can be sent to Notion as a page in a database; the user sees
the status and a link. Local only otherwise: no other external service, no AI API.

### B1. Research first (WebFetch developers.notion.com), write findings into the PR description
`Notion-Version` header value; databases vs data sources (the 2025+ API split); create page in a database;
append block children (max 100 blocks per request, max 2000 chars per rich text, nesting depth); rate limit
(~3 req/s, 429 + `Retry-After`); error format.

### B2. Settings
- `%LOCALAPPDATA%\VisionTrack\settings.json`: `{"notion": {"token": "...", "databaseId": "...", "autoExport": false}}`,
  edited by hand until stage 8. Never logged, never committed (`settings.json` is in `.gitignore`).
- `config/SettingsService`: reads the file on each use, `@JsonIgnoreProperties(ignoreUnknown = true)`, logs no
  content on parse errors, missing file = Notion not configured.

### B3. Code (package `meeting`, or `meeting/notion`)
- `NotionClient` with Spring `RestClient` (no new heavy dependency): create page, append children, retry with
  backoff on 429/5xx honouring `Retry-After`, ~3 requests/s.
- `NotionPageBuilder` (pure, unit-tested): properties Name, Date, Duration, Type, Project, Tags, Participants;
  body Summary → Decisions → Action items as `to_do` blocks (owner, due) → Timeline → Transcript inside a `toggle`;
  split text to ≤ 2000 chars per rich text and blocks to ≤ 100 per request.
- `NotionExporter implements NotesExporter` (interface exists: `export(Path folder, Meeting, MeetingNotes, String
  model)`; `MarkdownExporter` is the other implementation, used in `MeetingJobService`).
- `Meeting`: add export state (`NOT_EXPORTED | EXPORTING | EXPORTED | FAILED`), Notion page URL, last error; keep
  `meeting.json` backward compatible (old files without the fields still load).
- REST: `POST /api/meetings/{id}/notion` (send / retry); optional auto-export after processing when
  `autoExport` is true. Export never blocks or fails meeting processing.
- UI (`index.html`, Meetings tab): "Send to Notion" / "Retry" button, status, link to the page; a clear message when
  Notion is not configured.

### B4. Tests
- Page builder: limits (long transcript → several requests, rich text split at 2000), Russian and English text,
  empty sections omitted.
- Client: request sequence, headers (`Authorization`, `Notion-Version`), 429 retry, against a stub server
  (`com.sun.net.httpserver.HttpServer`).
- `meeting.json` without export fields still loads.

### B5. Acceptance (needs the user)
The user creates a Notion integration, shares a database with it and puts token + database id into
`settings.json` (never into the chat). Then: process a short test meeting (or reuse a `READY` one), send it,
check the page (sections, to-dos, toggle with transcript), send again → no duplicate page (update or refuse),
break the token → clear error and Retry works after fixing it. Delete the test page and meeting afterwards.

## 4. Optional, if time is left (ask before starting)
- Terraform remote state: S3 backend with lockfile in the main account (state is local in
  `infra/terraform.tfstate.d/main/` today).
- Meeting metadata (not audio, not transcript text) as a third table in the data lake; only with the user's OK on
  what leaves the PC.

## 5. Out of scope for this iteration
Stage 8 Settings tab, stage 9 build for a friend, speaker separation, dbt/dashboards, Firehose, GPU in AWS.
