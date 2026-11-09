# Contributing

This is the shared core of the ai.pipestream gRPC connectors. Patches
should look like they belong in an Apache-style Java project: typed wire,
comments that pass lint, tests that do not need the internet.

## Before you send a PR

```
./gradlew build          # unit tests + javadoc (-Xdoclint:all -Xwerror)
buf lint                 # STANDARD + COMMENTS on grpc-sync-api
```

`buf` is not a Gradle plugin here (codegen stays protoc). Install from
https://buf.build or use the version CI pins (`1.54.0`).

A change here can break the connector repos. Before merging an API
change, build `grpc-confluence` and `grpc-microsoft` against your
checkout: put them next to this directory (or pass
`-PintegrationDir=...`) and run `./gradlew build` there. Their
`settings.gradle.kts` includes this build, so no snapshot publish is
needed.

## Rules that already bit us

- **Source-neutral.** No module here depends on a source API or imports a
  source proto. Source-specific keys, OKF catalog mapping, output
  formats, MCP tools, and Kafka Connect sources live in the connector
  repos and register through `OkfCatalogSource`, `OutputFormat`,
  `McpToolProvider`, or Kafka Connect's `SourceConnector` ServiceLoader
  entries.
- **No JSON bridge** on the typed gRPC path. Domain messages stay
  protobuf. `McpJson` is for the MCP edge only.
- **Do not invent shared modules.** The connection catalog and ledger
  live behind `ConnectionService` / `SyncTableService` gRPC; callers do
  not open the store. The output SPI plus filesystem / S3 ServiceLoader
  jars is the destination seam; do not fold a store into a connector.
- **Wire compatibility.** Proto packages and Java packages do not change.
  `buf breaking` with `WIRE_JSON` against `main` should stay clean.
- **Secrets stay out of git, logs, and PR text.**
- **Buf COMMENTS is strict.** Every service, rpc, message, field, oneof,
  enum, and enum value needs a non-empty comment.
- **Javadoc is strict** on handwritten public/protected API. Generated
  protobuf is excluded.
- **Virtual threads.** gRPC handlers and MCP tool calls are blocking by
  design; the servers run them on virtual threads. Do not introduce a
  platform-thread pool for I/O.

## Layout

See [docs/architecture.md](docs/architecture.md). `*-api` is proto,
`*-service` is a process.
