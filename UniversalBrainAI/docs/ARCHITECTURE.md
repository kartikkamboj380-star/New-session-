# Universal Brain AI - Architecture

## Module mapping (requested tree -> implementation)

The requested modular tree is implemented as one Gradle application module
(`:UniversalBrainAI:app`) with clean-architecture **packages** mirroring it.
Rationale: single-module builds are dramatically faster and more reliable on CI,
and every boundary below is still enforced by package conventions. The other
directories exist as documentation/tests where noted.

| Requested | Actual |
|---|---|
| `app/` | `UniversalBrainAI/app/` (Gradle app module, manifest, resources) |
| `core/common` | `com.universalbrain.ai.core.*` shared primitives |
| `core/database` | `com.universalbrain.ai.core.database` (Room, 13 tables) |
| `core/security` | `com.universalbrain.ai.core.security` (Android Keystore AES/GCM) |
| `core/networking` | `com.universalbrain.ai.core.networking` (OkHttp pool client) |
| `core/design` | `com.universalbrain.ai.core.design` (dark graphite/green/blue theme) |
| `feature/*` | `com.universalbrain.ai.feature.*` (Compose screens + ViewModels) |
| `domain/` | `com.universalbrain.ai.domain.*` (pure, unit-tested logic) |
| `integrations/` | `com.universalbrain.ai.integrations.*` (mining pools, LLM, ads ledger) |
| `docs/` | `UniversalBrainAI/docs/` |
| `tests/` | `UniversalBrainAI/app/src/test/` (JVM unit tests) |

## AI Brain pipeline

```
user text
   -> CommandParser (normalize, params: today/all/number)
   -> IntentAnalyzer (scored keyword intents, 15 intents)
   -> DecisionEngine (Plan of tool calls; destructive steps flagged)
   -> [chat UI: confirmation for flagged plans]
   -> AIBrain TaskExecutor (ToolRegistry tools with READ/EXECUTE/WRITE permissions)
   -> MemoryManager (Room-backed key/value operational memory)
   -> streamed AgentEvents (thinking / tool status / final answer)
```

Optional LLM bridge: Settings can point at any OpenAI-compatible
`/chat/completions` endpoint with a Keystore-stored key; on failure the local
brain answers. The agent never hides an action behind "AI magic".

## Data model (Room, 13 tables)

imported_apps, instances, accounts, account_sessions, mining_workers,
mining_samples, earnings, ai_messages, ai_memory, automation_rules,
background_tasks, notifications, activity_logs.

Secrets are never stored in Room: `SecureStore` (Android Keystore, AES/GCM).

## Background operations

- `BrainService`: foreground service (specialUse) — vitals sampling, rule
  evaluation, worker polling, alerts; pause/resume/stop via notification or UI.
- WorkManager: `MiningPollWorker` (periodic polling, min 15 min), `RuleEvalWorker`
  (30 min rule check) — survive process death.
- Intervals adapt: doubled on battery temp >= 43C or battery < 20% unplugged;
  low-RAM devices sample at half rate.

## Honest scope (what is NOT claimed)

- Cloning: WEB instances are genuinely isolated (own process + WebView data
  directory, 2 slots). APP instances launch installed apps through the platform;
  Android does not permit in-process cloning of arbitrary apps and this app
  does not pretend otherwise. The instance count adapts to device RAM (policy).
- Mining: monitoring + optimization of EXTERNAL workers through documented pool
  stats APIs; no on-device mining (unprofitable and hardware-damaging).
- Ads: consent + reward ledger; no ad SDK is bundled without real IDs.
- AI: deterministic local NLU (not an LLM) + optional user-configured bridge.

## v1.1 upgrade (device-feedback round, 2026-11)

Real-device feedback drove three upgrades, all honest and unit-tested:

1. **Session isolation (the ctpool problem).** WEB instances now use multi-profile
   WebView (`androidx.webkit` ProfileStore, WebView 116+): a named Profile per
   instance with its own cookie store/data directory, falling back to
   per-process data-dir suffixes. Each instance carries `startUrl` +
   `freshStart` (DB v1 -> v2 migration, non-destructive): fresh-start clears
   cookies/storage before loading the start URL, so every launch opens the
   site's sign-in page. APP instances now relaunch the app UI from its start
   screen (`FLAG_ACTIVITY_CLEAR_TASK`); the UI states plainly that Android
   keeps the app's own stored login — per-account sessions need WEB clones
   (paste the app's website URL) or MIUI Dual apps.
2. **Stronger brain.** IntentAnalyzer grew from 15 to 33 intents with synonym
   scoring, verb-target extraction ("open ctpool"), number-words, URL/wallet/
   threshold/quoted-name parsing and nearest-intent suggestions for unknowns.
   The tool registry grew from 12 to 28 tools: real create/delete/configure of
   instances, app launching, app import, accounts, worker registration,
   pause/resume, apply-optimization (writes poll settings), automation rules,
   battery saver, recommendations. A `RecommendationEngine` scans live state
   and surfaces prioritized, tappable actions on the Dashboard; the optional
   LLM bridge now receives a live state summary.
3. **Low-end phones + ads.** `PerformanceProfile` (LOW/MID/HIGH + battery
   saver) drives vitals sampling rate, mining poll cadence, page sizes and
   animation flags; low-RAM devices cap at 2 instances and 2 workers. The ads
   module adds points/cap/streak math (`AdsEngine`) and a real ledger table
   (`ad_rewards`) written ONLY by a genuine SDK reward callback
   (`UbaViewModel.recordAdReward`) — with no SDK bundled, every surface says
   ads cannot be served yet. Nothing is simulated.
