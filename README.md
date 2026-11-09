# grpc-integration

The shared, source-neutral core of the ai.pipestream gRPC connectors. It
holds everything a connector needs that is not about one upstream API:
the sync ledger and connection catalog, the output SPI with its
filesystem and S3 stores, the Open Knowledge Format / WARC writer, the
combined MCP server, and the Kafka Connect scaffolding.

Connectors live in their own repositories and consume these modules as
Maven dependencies:

| Repository | Connector |
|---|---|
| [grpc-confluence](https://github.com/ai-pipestream/grpc-confluence) | Confluence Cloud REST v2 as gRPC |
| [grpc-microsoft](https://github.com/ai-pipestream/grpc-microsoft) (currently `grpc-sharepoint`) | Microsoft Graph sites, drives, and files as gRPC, plus the Copilot (GCA) connector |

Nothing here imports a source API or a source proto. Sources plug in
through `ServiceLoader` seams (see [Extension points](#extension-points)).

See [docs/architecture.md](docs/architecture.md) for process topology and
[CONTRIBUTING.md](CONTRIBUTING.md) for the lint bar.

## Modules

Group `ai.pipestream.integration`, version `0.1.0-SNAPSHOT`.

| Module | Port | What it is |
|---|---|---|
| `grpc-sync-api` | | Ledger and connection catalog proto contract (`ai.pipestream.sync.v1`). |
| `grpc-sync-service` | 9097 | Asset ledger plus `ConnectionService`. Memory or SQLite. |
| `grpc-okf` | | Writer for Open Knowledge Format v0.2 bundles and a sibling WARC 1.1 archive. Sources plug in through `OkfCatalogSource`. Test fixtures carry a WARC parser. |
| `grpc-output-spi` | | Output seam. Pick a store (filesystem, S3) and formats (OKF, protobuf, JSON, plus source formats). |
| `grpc-output-filesystem` | | Writes artifacts to a local directory. |
| `grpc-output-s3` | | Writes artifacts to S3 under the source's path keys. |
| `grpc-mcp` | 8090 | The one combined Streamable HTTP MCP server. Core tools plus every `McpToolProvider` on the classpath. |
| `grpc-connect` | | Kafka Connect base classes for sources that emit protobuf change bytes. Test fixtures carry `SourceTaskContext` fakes. |

## Extension points

All of them are `java.util.ServiceLoader` registrations under
`META-INF/services/`, implemented in the connector repos:

| Interface | Module | What a connector registers |
|---|---|---|
| `ai.pipestream.okf.OkfCatalogSource` | `grpc-okf` | How its crawl maps to an OKF catalog. |
| `ai.pipestream.output.OutputFormat` | `grpc-output-spi` | Extra output formats (for example `microsoft-connector`). |
| `ai.pipestream.output.OutputStore` | `grpc-output-spi` | Extra stores (filesystem and S3 ship here). |
| `ai.pipestream.mcp.McpToolProvider` | `grpc-mcp` | Its MCP tools, built over a channel the server dials to the provider's target. |
| `org.apache.kafka.connect.source.SourceConnector` | `grpc-connect` | Its connector, a subclass of `ChangeSourceConnector` whose task extends `ChangeSourceTask`. This is Kafka Connect's own plugin discovery. |

## Using it from a connector repo

Connector repos declare `ai.pipestream.integration:<module>:0.1.0-SNAPSHOT`
dependencies and resolve them from the ai.pipestream Forgejo registry
(`https://git.rokkon.com/api/packages/ai-pipestream/maven`, no credentials
needed to read). When a `grpc-integration` checkout sits next to the
connector checkout, the connector's `settings.gradle.kts` includes it as a
Gradle composite build and the dependencies come from source instead, so
no registry access is needed:

```
work/
  grpc-integration/
  grpc-confluence/      # includeBuild("../grpc-integration")
  grpc-microsoft/
```

`-PintegrationDir=/path/to/grpc-integration` points at a checkout
somewhere else.

## Publishing

Every module applies `maven-publish`. Snapshots go to the Forgejo registry
when `FORGEJO_TOKEN` (and optionally `FORGEJO_ACTOR`) is set; without the
token there is no remote repository and nothing leaves the machine.
`.forgejo/workflows/publish-snapshot.yml` builds and publishes on every
push to `main`.

```
FORGEJO_TOKEN=... ./gradlew publishAllPublicationsToRegistryRepository
./gradlew publishToMavenLocal     # local testing
```

## Output

Each connector's completed `Sync` can write crawl artifacts through the
output SPI. `OutputStore` implementations are ServiceLoader jars:
filesystem is the default; `grpc-output-s3` is optional
(`OutputStores.load().has("s3")` is true only when the S3 jar is on the
classpath).

```
OUTPUT_DIR=/data/okf/run              # alias: OKF_DIR
# OUTPUT_STORE=filesystem             # default; set s3 when grpc-output-s3 is loaded
# OUTPUT_FORMATS=okf                  # also: protobuf,json, plus source formats
# OUTPUT_PREFIX=run-2026-08-23
# OUTPUT_S3_BUCKET=knowledge
# OUTPUT_S3_PREFIX=confluence
# OUTPUT_S3_REGION=us-east-1
```

OKF trees land under `{prefix}/okf/` with `bundle.zip` and
`bundle.warc.gz` beside them. WARC is a per-URI archive (ISO 28500): one
`resource` record per live `web_url`, a `conversion` record for the OKF
markdown, and an HTML collection page whose links are those same URIs.
The zip is **not** stored inside the WARC. Protobuf is the same binary
Kafka Connect publishes; JSON is a file export, not the gRPC wire.

## Sync table and connections

A source-agnostic gRPC ledger of where each asset lives, its phase
(initial crawl / update / delete), sync status, and whether it is an
attachment. Connector `Sync` streams write it; MCP and any other client
can query it.

```
SYNC_TABLE_GRPC_PORT=9097
SYNC_TABLE_JDBC_URL=jdbc:sqlite:/data/sync-table.db   # or SYNC_TABLE_DB=/data/sync-table.db
./gradlew :grpc-sync-service:run
```

RPCs: `UpsertAsset`, `GetAsset`, `ListAssets` (stream), `Watch` (stream),
`DeleteAsset`, `Reconcile`, `GetCheckpoint`, `PutCheckpoint`.
`ConnectionService` on the same port is the connection catalog (create,
get, list, update, delete, record-probe, plus process settings).
Connectors, MCP, and a future UI call the generated stub; they never open
the SQLite file.

`asset_id` is `{source}:{connection_id}:{kind}:{native_id}`. After a full
crawl a connector calls `Reconcile` with that run's id so rows not seen
this pass become `DELETED`. Attachments set `attachment=true` and
`parent_asset_id`. The store is in-memory unless `SYNC_TABLE_JDBC_URL` or
`SYNC_TABLE_DB` points at SQLite. Handlers run on virtual threads.

`Connection` still carries a `ConnectionKind` enum (`CONFLUENCE`,
`MICROSOFT`) and per-source credential fields. Removing them would change
the wire, so they stay for now.

## MCP (Streamable HTTP)

One server, one `/mcp` endpoint, MCP Java SDK 2.0.1 (spec 2025-11-25) on
the same shaded Netty as the gRPC processes. Tool handlers run on virtual
threads and consume the streaming gRPC RPCs, returning a bounded JSON
summary.

Core tools (call `ConnectionService` / `SyncTableService` on
`SYNC_TABLE_TARGET`, default `localhost:9097`):

```
app_status / app_set_output / app_set_kafka
connection_create / connection_list / connection_get / connection_update
connection_set_output / connection_delete
sync_table_get_asset / sync_table_list_assets
```

Source tools come from `McpToolProvider` jars on the classpath. The
server dials one plaintext channel per provider, to the provider's target
variable (`CONFLUENCE_GRPC_TARGET`, `MICROSOFT_GRPC_TARGET`, ...), and
`app_status` reports each as `<name>Target`. A deployment that wants
Confluence and Microsoft tools puts `grpc-mcp` plus `grpc-confluence-mcp`
and `grpc-microsoft-mcp` (and their runtime dependencies) on one
classpath.

```
MCP_PORT=8090
SYNC_TABLE_TARGET=localhost:9097
./gradlew :grpc-mcp:run
```

Do not call unbounded `Watch` from a tool; use `ListAssets`. Secrets are
write-only. Get, list, and MCP rows return `hasToken`, never the token.

## Kafka Connect

`grpc-connect` is a library, not a plugin. A connector repo extends
`ChangeSourceConnector` (shared `topic`, `grpc.target`, `grpc.plaintext`
keys; one task) and `ChangeSourceTask` (one `Sync` pass or one crawler
pass per poll, protobuf bytes as values, the resume cursor as the Connect
offset), registers the connector in
`META-INF/services/org.apache.kafka.connect.source.SourceConnector`, and
builds its own plugin zip with this jar inside.

## Proto lint (Buf)

`grpc-sync-api` lints with Buf **STANDARD** plus **COMMENTS**. Codegen
stays `protoc` via the Gradle protobuf plugin; Buf is lint-only.

```
buf lint
```

## Build and tests

Java 25 toolchain. `./gradlew build` runs the unit suite and javadoc
(`-Xdoclint:all -Xwerror` on handwritten API). Every compiler warning
(`-Xlint:all -Werror`) and every Gradle deprecation
(`org.gradle.warning.mode=fail`) fails the build.

## Docker

```
docker build -t grpc-sync -f Dockerfile.sync .
docker build -t grpc-mcp -f Dockerfile.mcp .
```

The MCP image puts `/opt/mcp/lib/*` and `/opt/mcp/ext/*` on the
classpath. Add connector tool jars to `/opt/mcp/ext` in a derived image
or a mount.
