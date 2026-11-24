# AGENTS.md: Confluence and SharePoint Online connector workspace

This file is for coding agents and LLM reviewers. It covers the whole
workspace: the shared core (this repository), the Confluence connector, the
Microsoft (SharePoint Online, OneDrive, Entra) connector, and the two
repositories that consume their ACL data. Each connector repository has a
short `AGENTS.md` of its own that points here and adds repo-specific detail.

Snapshot date: 2026-11-24. Branch heads, PR numbers and "proven live" lists
change daily; check the PRs before relying on them.

## 1. What the workspace is

The organisation builds a pipeline that indexes any kind of document into an
enterprise semantic search index. These repositories are the source side for
Confluence Cloud and Microsoft 365: each vendor REST API is exposed as a
strongly typed gRPC service that reads, writes and streams changes, and the
access-control data each source holds is carried through so search can trim
results per caller.

```
                       grpc-mcp :8090 (one MCP endpoint, tools from McpToolProvider jars)
                              |
        +---------------------+----------------------+
        v                     v                      v
 ConfluenceService     MicrosoftService         SyncTableService +
   :9095                 :9096                  ConnectionService :9097
 (grpc-confluence)     (grpc-microsoft)         (grpc-integration)
        |  REST v2 / v1       |  Graph + SharePoint REST     ^
        v                     v                              |
 Confluence Cloud      Microsoft Graph / SPO        ledger rows from every Sync
        |                     |
  Forge app queue       Graph change notifications
  (change stream)       (change stream)
        |                     |
        +---- Sync / WatchChanges streams of typed records ----+
                              |
             Kafka Connect source, OKF/WARC output, sync ledger
                              |
                              v
        protomolt (maps native ACL records into DocumentSecurity with CEL config)
                              |
                              v
        protomolt-opensearch (protomolt-acl plugin filters search and get)
```

| Repository | Role | Proto package |
|---|---|---|
| [grpc-integration](https://github.com/ai-pipestream/grpc-integration) (this repo) | Source-neutral core: sync ledger and connection catalog, output SPI (filesystem, S3), OKF / WARC writer, the combined MCP server, Kafka Connect base classes, the shared `AclCompleteness` marker | `ai.pipestream.sync.v1` |
| [grpc-confluence](https://github.com/ai-pipestream/grpc-confluence) | Confluence Cloud REST v2 (plus v1 where v2 has no route) as gRPC; reads, writes, `Sync`, ACL records; Forge app for change events | `ai.pipestream.confluence.v1` |
| [grpc-microsoft](https://github.com/ai-pipestream/grpc-microsoft) (GitHub tooling may still call it grpc-sharepoint) | Microsoft Graph (sites, drives, files, lists, Entra users and groups) plus SharePoint REST where Graph has no route; separate admin service for site grants; Copilot (GCA) connector process | `ai.pipestream.microsoft.v1` |
| [protomolt](https://git.rokkon.com/ai-pipestream/protomolt) (Forgejo is the source of truth; GitHub is a mirror) | The platform. Its repo service stores documents; the ACL work maps connector records into `repo/v1/DocumentSecurity` | `ai.protomolt.*` |
| [protomolt-opensearch](https://github.com/ai-pipestream/protomolt-opensearch) | `protomolt-acl` OpenSearch plugin: runs beside the built-in Security plugin and filters search, count, msearch and document reads by the ACL fields | none (plugin) |

`ai.pipestream.integration.v1` (the planned connector-platform contract:
entity envelope, catalog, hooks, sinks, CEL Map / Project) is a design under
review, tracked in grpc-confluence issues #11 to #21. It is not built yet.

## 2. Where the code under review lives

`main` of the core and both connectors now holds the read side, the write
rpcs, the ACL data and the Microsoft admin service. These merged:

| Repo | Merged PRs |
|---|---|
| grpc-integration | #3 `AclCompleteness` marker, #4 certificate credential on catalog connections, #6 catalog entries whose resource was not fetched |
| grpc-confluence | #25 all v2 write rpcs, ADF model, v1 attachments and labels, app properties through Forge; #28 ACL data (group rpcs, `GetContentAccess`, `CheckContentPermission`, Sync access records); #44 ledger failures fail the `Sync`; #46 custom content proven live |
| grpc-microsoft | #2 write rpcs, Entra and SharePoint security reads, term columns, `grpc-microsoft-admin`; #20 ledger failures fail the `Sync` |

Still open, so review these branches as well as `main`:

| Repo | PR | Branch | Base | What |
|---|---|---|---|---|
| grpc-confluence | #29 | `claude/project-thread-sbyfoc` | `main` | `ConfluenceChangeService.WatchChanges` and the `grpc-confluence-forge` app |
| grpc-microsoft | #4 | `claude/project-thread-sbyfoc` | `claude/project-thread-i7updt` (already merged as #2) | `MicrosoftChangeService`: delta passes, Graph subscriptions, notification receiver |
| protomolt | #933 (Forgejo) | `claude/project-thread-wfp1ob` | `main` | Identity expansion, `required_rule_sets`, CEL mapping of Confluence and SharePoint ACL records, tests on recorded live records |
| protomolt-opensearch | #1 | `claude/project-thread-jd2zws` | `main` | The `protomolt-acl` plugin |

The same branch name is used across repositories on purpose: connector CI
builds against the grpc-integration branch of the same name when it exists,
otherwise against grpc-integration `main`.

## 3. Check out and build

Lay the repositories out side by side. Each connector's
`settings.gradle.kts` includes `../grpc-integration` as a Gradle composite
build when it is there, so the core builds from source and no registry is
needed.

```
work/
  grpc-integration/   # check out the same branch name as the connector when it exists
  grpc-confluence/
  grpc-microsoft/
```

```
cd grpc-confluence && ./gradlew build          # or -PintegrationDir=/path/to/grpc-integration
cd grpc-microsoft  && ./gradlew build
buf lint                                       # in each repo; buf 1.54.0
```

- Java 25 toolchain for the core and both connectors. The OpenSearch plugin
  uses Java 21 and OpenSearch 3.10.0.
- `./gradlew build` runs unit tests, fake-server tests and the replay of
  recorded live exchanges. It never talks to Atlassian or Microsoft.
- protomolt-opensearch's `./gradlew build` starts OpenSearch and Keycloak
  containers, so it needs Docker. protomolt uses its own docker compose
  infrastructure; see its `AGENTS.md`.
- Do not change how tests are run (task wiring, compose and container setup,
  CI jobs). Review them; propose changes in findings.
- Without a sibling checkout, the core resolves as
  `ai.pipestream.integration:*:0.1.0-SNAPSHOT` from the Forgejo registry.
  Publishing to it is not set up yet, so prefer the sibling checkout.

## 4. Standing rules (review against these)

These come from the project owner. Code that breaks one is a finding even
if it works.

**Platform**
- No Quarkus in these repositories, and nothing brought into protomolt may
  use Quarkus. Plain Java, Gradle, grpc-netty-shaded, `ServiceLoader` SPIs,
  virtual threads for blocking handlers.
- Pure gRPC on our surface. No REST endpoints of ours. Outbound REST to the
  vendor and inbound vendor callbacks (Graph notifications, the Forge
  trigger) are the only HTTP.
- A connector can be written in any language; the proto contract is the
  product, the Java is a reference implementation.

**Contracts**
- Strongly typed, 100% gRPC contract. No JSON strings, no
  `google.protobuf.Struct`, `Value`, `ListValue` or `Any` in
  `confluence.v1` or `microsoft.v1`. Free-form vendor JSON (ADF bodies,
  Confluence property values, SharePoint column values) is modelled as
  proto messages. Closed sets are enums. One typed request and response per
  rpc.
- Jackson is allowed only inside the server, at the edge toward the vendor
  REST API.
- Buf STANDARD plus COMMENTS: every service, rpc, message, field, oneof,
  enum and enum value has a non-empty comment. `buf breaking` against
  `main` should stay clean; deprecate, never retype or renumber.
- Full CRUD coverage of the vendor API, not read-only. The Confluence v2
  spec is vendored at `grpc-confluence/docs/upstream/atlassian/`; the gap
  analyses in each repo's `docs/contracts/` map rpcs to routes. Narrowing
  scope ("writes are out", "skip this route") is the owner's decision, so
  flag it rather than accept it.
- Never round-trip protobuf through JSON or `Struct` for internal work.
- Mapping between types (connector records into protomolt documents, write
  messages from protomolt assets) is backend config with CEL selectors and
  dynamic mappers, not hand-written mapping code.

**Build**
- The build fails on any warning: `-Xlint:all -Werror`, javadoc
  `-Xdoclint:all -Xwerror`, `org.gradle.warning.mode=fail`. Suppressing a
  warning to get green is a finding.
- Javadoc and proto comments say what a thing does, briefly. No design
  rationale, no history, no chat context in comments.

**Limits**
- No hardcoded small payload caps (no "25 MiB" constants). Limits belong to
  the vendor system and come from configuration (for example
  `CONFLUENCE_MAX_ATTACHMENT_BYTES`, where 0 means unknown). The only cap
  agreed so far is Redis's 512 MB maximum.

**Errors and data integrity**
- No silent failure. A refused or failed vendor call, ledger write or
  output write must surface as a gRPC status or a typed in-band result, not
  only a log line. A `Sync` must not hand out a resume cursor past data it
  failed to record. Fixes are made for real: no skipped tests, no loosened
  assertions.
- ACL reads are all or nothing per owner. A partial read is marked
  `ACL_COMPLETENESS_UNAVAILABLE` and consumers deny by default.

## 5. Testing model

One scenario suite per area runs against two backends with the same
assertions:

| Mode | Backend | When |
|---|---|---|
| record | the real test tenant, through a recording transport | opt-in live tasks, normally CI `workflow_dispatch` |
| replay | committed recordings, no network | every `./gradlew build` |

- Recordings live under `src/test/resources/recordings/` in each service
  module. They are written only by live runs, never by hand. Read each
  module's `recordings/README.md` for the layout.
- Every recording is scrubbed before it is written (hosts, account and
  object ids, emails including percent-encoded ones, display names, tokens,
  cursors). Hygiene tests (`RecordingsGuardTest`, `RecordingsHygieneTest`)
  reject any committed file that still looks like tenant data. A scrubber
  gap is a high-severity finding.
- Replay is strict: each request must equal the next recorded one. A
  scenario with no recording is reported as skipped, not passed.
- Fake-server tests (`FakeConfluenceServer`, `FakeGraphServer`) remain for
  cases with no recording yet; they are meant to be replaced by replay.
- Live tasks: `liveSmokeTest`, `liveCoverageTest`, `liveWriteTest`,
  `liveAclTest`, `liveChangesTest`, `liveAppPropertiesTest` and
  `liveAdminTest`, each gated by environment variables and excluded from
  `test`. Live writes stay inside one test space (Confluence `CLAUDEGRPC`)
  or one test site (SharePoint `WelcomeToPipestream`) under a uniquely
  named parent that the run deletes.

**Definition of done:** a feature is done only when it is proven against
the real Confluence and Microsoft test accounts and its recording replays
in the build. Green CI on fakes is not done. When a PR says something is
"not proven live", treat it as unfinished.

Reviewers: do not run live tasks or dispatch CI workflows. They write to
real tenants, and some change tenant permissions.

## 6. Data flows worth tracing

**Sync.** `Sync` streams typed change records with a resume cursor.
Optional sinks: the sync ledger (`SYNC_TABLE_TARGET`), Kafka (raw protobuf
bytes), and output (OKF bundle, sibling WARC, protobuf, JSON file export)
through the output SPI. Ledger `asset_id` is
`{source}:{connection_id}:{kind}:{native_id}`. After a full sweep the
connector calls `Reconcile`, so rows not seen become `DELETED`.

**Change streams (PoC, open PRs grpc-confluence #29 and grpc-microsoft #4).**
- Confluence: a Forge app with no UI stores product events in Forge KVS
  (7 days). `ConfluenceChangeService.WatchChanges` polls the app's web
  trigger (`take` / `ack`, shared-secret header), re-reads what each event
  names, and emits typed changes between timer `Sync` passes. The server
  only calls out, so it needs no public URL.
- Microsoft: `MicrosoftChangeService.WatchChanges` runs delta passes on a
  timer; a Graph change notification starts a pass early. Subscription rpcs
  map 1:1 to Graph `/subscriptions`. `NotificationReceiver` (JDK
  `HttpServer`) is off unless `MICROSOFT_NOTIFICATION_PORT` is set and checks
  `clientState` in constant time.

**ACL data (connector side on `main`; protomolt #933 and protomolt-opensearch #1 open).**
- No ACL model of our own. Each connector emits the source's native ACL
  records; protomolt maps them with CEL config into `DocumentSecurity`.
- Confluence: `Sync` emits `SpaceAccess`, a `ContentAccessChain` per item
  (the item, then each ancestor that can carry restrictions), groups, group
  members and users. Read access is the space AND every view-restricted
  ancestor AND the item's own restriction, which protomolt expresses as
  `required_rule_sets`.
- SharePoint: `Sync` with `include_security` emits site groups with
  members, site admins, and `SiteAcl` records for the site and every list
  and item with unique role assignments; every drive, list and item carries
  `acl_owner_id` naming the `SiteAcl` it inherits from. Entra users and
  groups come from Graph (`ListUsers`, `DeltaGroups`, transitive membership).
- The identity join between the two systems is Entra, by email for now
  (mail, UPN, proxy addresses, other mails).
- Permissions are split: the connector app holds Graph and SharePoint
  `Sites.Selected` plus a per-site grant, and `User.Read.All` /
  `GroupMember.Read.All`. Only `grpc-microsoft-admin` runs with
  `Sites.FullControl.All`, and it is deployed on its own. SharePoint REST
  app-only calls need the certificate credential, not a secret.
- OpenSearch: a document is visible only if `acl_complete` is true,
  `acl_allow` holds one of the caller's keys, `acl_deny` holds none, and
  every nested `acl_gates` entry holds one. Caller keys come from the
  Security plugin (user, backend roles) plus the plugin's principal store.

## 7. What to focus a review on

1. **Silent errors and fake success.** Catch blocks that log and continue,
   empty results on 3xx or empty bodies, 4xx swallowed on subtree walks,
   cursors advanced past unrecorded data, outputs that skip without
   signalling. Many are already filed (see section 8); look for more of the
   same shape.
2. **Contract fidelity.** Vendor fields that never reach a typed proto
   field, enums missing values the live API returns, routes with no rpc,
   untyped holes. Use `docs/contracts/*` and the vendored spec.
3. **ACL correctness.** Anything that could grant more than the source
   does: inheritance and `acl_owner_id` staleness, Limited Access,
   anonymous and org-wide sharing links, access classes, completeness
   markers set to COMPLETE after a refused read, case handling of keys.
4. **Recording hygiene.** Tenant data or credentials that could slip past
   the scrubber; replay matching that is looser than it claims.
5. **Concurrency and streaming.** Flow control on server streams and bidi
   rpcs (`UploadItem`, `Mutate`), virtual-thread use, unbounded buffering,
   retries that are not idempotent.
6. **Write safety.** `expected_etag` / If-Match handling, retries on
   non-idempotent POSTs, dry runs that are not dry, cleanup paths in live
   tests.
7. **Rule breaks** from section 4 (JSON, Quarkus, caps, warnings
   suppressed, verbose comments).
8. **Cross-repo seams.** Behaviour that depends on a sibling repository's
   branch, ports (the admin service and the sync ledger both default to
   9097), shared ids and placeholders.

## 8. Known issues (do not re-file)

- grpc-confluence #33 and grpc-microsoft #8: code review of 2026-11-23
  (silent errors, fake success, JSON hacks), and the issues filed from it
  that are still open: grpc-confluence #35 to #43; grpc-microsoft #10, #12
  to #18 and #23. The ledger-failure issues (grpc-confluence #34,
  grpc-microsoft #11) are fixed on `main`.
- grpc-confluence #27 / grpc-microsoft #3: ACL follow-ups.
- grpc-confluence #30, #31, #32: Forge deploy, events Forge does not send,
  delivery loss.
- grpc-microsoft #5, #6, #7: subscription renewal, admin-side permission
  change detection, Entra group and user change feed.
- Not proven live, by the owner's choice or tenant limits: Confluence data
  classification and Guard Premium redaction; SharePoint term store reads
  (Microsoft serves them to delegated sign-in only); tenant-wide site
  listing with the restricted app. Confluence custom content is proven live
  against a Forge custom content type (#46).

## 9. Reporting findings

- For each finding give: repository, branch, file and line, what is wrong,
  a concrete input or sequence that triggers it, and the severity (data
  loss or ACL leak, wrong result, rule break, nit).
- Separate findings in code you were asked to review from pre-existing
  problems on `main`.
- Say what you checked and what you could not (for example "replay passes,
  not checked against a live tenant").

## 10. Safety

- Never commit, print or paste secrets, tokens, certificates, tenant ids or
  personal email addresses. Live credentials exist only as CI secrets and
  in local environments; reviewers do not need them.
- Do not push to `main`, force-push, or rewrite shared branches; other
  branches are stacked on them. Work goes through pull requests.
- Do not change tenant permissions, app registrations or site grants.
