# Architecture

Standalone Java processes expose upstream systems (Confluence Cloud,
Microsoft Graph) as typed gRPC. Each connector lives in its own
repository. This repository is what they share: a generic **sync-table**
gRPC service that is the current database of where a source asset lives
and whether it has been crawled, updated, or deleted; the connection
catalog; the output SPI; the OKF / WARC writer; one combined MCP
endpoint; and the Kafka Connect scaffolding.

```
                    ┌─────────────┐
   MCP clients ────►│  grpc-mcp   │  :8090  /mcp  (Netty + VT)
                    └──────┬──────┘   core tools + McpToolProvider jars
           gRPC plaintext  │
     ┌─────────────────────┼─────────────────────┐
     ▼                     ▼                     ▼
┌─────────────┐     ┌─────────────┐       ┌─────────────┐
│ confluence  │     │  microsoft  │       │  sync-table │  (this repo)
│   :9095     │     │    :9096    │       │    :9097    │
└──────┬──────┘     └──────┬──────┘       └──────▲──────┘
       │  REST v2          │  Graph REST         │
       ▼                   ▼                     │
  Atlassian Cloud     Microsoft Graph            │
   (grpc-confluence)   (grpc-microsoft)          │
                                                 │
   Kafka Connect ──► <source>-connect ── Sync() ─┘
                     (extends grpc-connect; or in-process crawler)
```

When `SYNC_TABLE_TARGET` is set, each connector `Sync` also writes the
ledger (attachments included) and calls `Reconcile` after a full crawl so
rows not seen in that run become `DELETED`. The same process exposes
`ConnectionService`: create / get / list / update / delete /
record-probe. Connectors, MCP, and a future UI call that gRPC service;
they do not open the store. `asset_id` is
`{source}:{connection_id}:{kind}:{native_id}`. Reconcile is
connection-scoped so two sites of one source do not delete each other.

When `OUTPUT_DIR` / `OKF_DIR` or `OUTPUT_S3_BUCKET` is set, each completed
`Sync` writes crawl artifacts through the output SPI. `OutputStore`
implementations are ServiceLoader jars: filesystem is the default;
`grpc-output-s3` is optional. `OUTPUT_FORMATS` selects protobuf (the same
binary Kafka publishes), JSON (file export), OKF markdown + sibling WARC,
and any format a connector registers.

## Seams

| Seam | Registered by |
|---|---|
| `OkfCatalogSource` | `grpc-<source>-output` |
| `OutputFormat` / `OutputStore` | `grpc-<source>-output`, `grpc-output-filesystem`, `grpc-output-s3` |
| `McpToolProvider` | `grpc-<source>-mcp` |
| `SourceConnector` (Kafka Connect) | `grpc-<source>-connect` |

Connector repos depend on these modules as
`ai.pipestream.integration:*:0.1.0-SNAPSHOT`, resolved from the Forgejo
registry or, when a sibling `grpc-integration` checkout exists, from a
Gradle composite build.

## Processes

| Process | Port | Threading | Streaming |
|---|---|---|---|
| `grpc-sync-service` | 9097 | Netty + virtual-thread executor | `SyncTableService` + `ConnectionService` |
| `grpc-mcp` | 8090 | Netty HTTP + virtual-thread handlers | Streamable HTTP; tools consume gRPC streams and return a bounded JSON summary |

There is no JDK built-in MCP API. `grpc-mcp` uses MCP Java SDK 2.0.1
(spec 2025-11-25) on the same shaded Netty as the gRPC processes. Do
not call unbounded `Watch` from an MCP tool.

## Wire

Proto packages are `ai.pipestream.<domain>.v1`. Buf lint is STANDARD plus
COMMENTS (every service, rpc, message, field, oneof, enum, and enum value
commented). Codegen is `protoc` via the Gradle protobuf plugin, not
`buf generate`.

## Sync-table

The ledger owns phase: a re-upsert is `UPDATE` even when a crawler still
labels the write `INITIAL_CRAWL`. `DELETE` and `SNAPSHOT` stay
caller-controlled. Attachments set `attachment=true` and
`parent_asset_id`. `AssetStore` is the in-memory store; `JdbcLedger`
(SQLite via `SYNC_TABLE_JDBC_URL` or `SYNC_TABLE_DB`) is the durable one.
Both sit behind `SyncTableService` and `ConnectionService` on the same
port. Secrets on a connection are write-only; Get/List return
`has_token` instead.

`Connection.kind` (`ConnectionKind`) and the per-source credential
fields still name Confluence and Microsoft. That is the one place the
core wire knows its sources; moving them out would change the wire.
