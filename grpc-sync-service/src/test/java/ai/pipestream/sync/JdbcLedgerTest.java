package ai.pipestream.sync;

import ai.pipestream.sync.v1.Asset;
import ai.pipestream.sync.v1.Connection;
import ai.pipestream.sync.v1.ConnectionKind;
import ai.pipestream.sync.v1.ConnectionOutput;
import ai.pipestream.sync.v1.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLedgerTest {

    @TempDir
    Path temp;

    @Test
    void sqliteSurvivesReopen() throws Exception {
        String jdbc = "jdbc:sqlite:" + temp.resolve("ledger.db").toAbsolutePath();
        try (JdbcLedger first = JdbcLedger.open(jdbc)) {
            first.putConnection(Connection.newBuilder()
                    .setConnectionId("acme")
                    .setKind(ConnectionKind.CONNECTION_KIND_CONFLUENCE)
                    .setDisplayName("Acme")
                    .setToken("secret")
                    .setClientCertificatePem("key-and-certificate-pem")
                    .build(), true);
            first.upsert(Asset.newBuilder()
                    .setAssetId("confluence:acme:page:1")
                    .setSource("confluence")
                    .setConnectionId("acme")
                    .setKind("page")
                    .setNativeId("1")
                    .setTitle("Design")
                    .build());
            first.putSettings(RuntimeSettings.newBuilder()
                    .setKafkaBootstrapServers("kafka:9092")
                    .setOutput(ConnectionOutput.newBuilder()
                            .setStore("s3")
                            .setS3Bucket("knowledge"))
                    .build());
        }
        try (JdbcLedger second = JdbcLedger.open(jdbc)) {
            Connection row = second.getConnection("acme").orElseThrow();
            assertThat(row.getToken()).isEqualTo("secret");
            assertThat(row.getClientCertificatePem()).isEqualTo("key-and-certificate-pem");
            assertThat(row.getHasClientCertificate()).isTrue();
            assertThat(second.get("confluence:acme:page:1").orElseThrow().getTitle())
                    .isEqualTo("Design");
            assertThat(second.list("confluence", "", "", false,
                    ai.pipestream.sync.v1.AssetSyncStatus.ASSET_SYNC_STATUS_UNSPECIFIED,
                    0, "acme")).hasSize(1);
            assertThat(second.getSettings().getKafkaBootstrapServers()).isEqualTo("kafka:9092");
            assertThat(second.getSettings().getOutput().getS3Bucket()).isEqualTo("knowledge");
        }
    }

    @Test
    void scopedReconcileSurvivesReopenAndLeavesOtherScopesAlone() throws Exception {
        String jdbc = "jdbc:sqlite:" + temp.resolve("scoped.db").toAbsolutePath();
        try (JdbcLedger first = JdbcLedger.open(jdbc)) {
            first.upsert(row("a-kept", "drive-a", "run-1"));
            first.upsert(row("a-gone", "drive-a", "run-1"));
            first.upsert(row("b-unchanged", "drive-b", "run-1"));
            first.upsert(row("unscoped", "", "run-1"));
        }
        try (JdbcLedger second = JdbcLedger.open(jdbc)) {
            // The re-read of drive-a writes a-kept again, without repeating
            // its scope: the stored scope stays.
            second.upsert(row("a-kept", "", "run-2"));
            assertThat(second.get("microsoft:c1:drive_item:a-kept").orElseThrow().getScope())
                    .isEqualTo("drive-a");

            assertThat(second.reconcile("microsoft", "run-2", "drive_item", "c1", "drive-a"))
                    .isEqualTo(1);

            assertThat(status(second, "a-gone"))
                    .isEqualTo(ai.pipestream.sync.v1.AssetSyncStatus.ASSET_SYNC_STATUS_DELETED);
            assertThat(status(second, "a-kept"))
                    .isEqualTo(ai.pipestream.sync.v1.AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
            assertThat(status(second, "b-unchanged"))
                    .isEqualTo(ai.pipestream.sync.v1.AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
            assertThat(status(second, "unscoped"))
                    .isEqualTo(ai.pipestream.sync.v1.AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
            // A reconcile without a scope still covers every row.
            assertThat(second.reconcile("microsoft", "run-2", "drive_item", "c1")).isEqualTo(2);
        }
    }

    private static Asset row(String id, String scope, String runId) {
        return Asset.newBuilder()
                .setAssetId("microsoft:c1:drive_item:" + id)
                .setSource("microsoft")
                .setConnectionId("c1")
                .setKind("drive_item")
                .setNativeId(id)
                .setScope(scope)
                .setRunId(runId)
                .build();
    }

    private static ai.pipestream.sync.v1.AssetSyncStatus status(JdbcLedger ledger, String id) {
        return ledger.get("microsoft:c1:drive_item:" + id).orElseThrow().getStatus();
    }

    @Test
    void ledgersOpenRespectsJdbcUrl() {
        Path file = temp.resolve("from-env.db");
        Ledger ledger = Ledgers.open(Map.of(Ledgers.ENV_JDBC_URL,
                "jdbc:sqlite:" + file.toAbsolutePath()));
        assertThat(ledger).isInstanceOf(JdbcLedger.class);
        ledger.close();
        assertThat(file).exists();
    }
}
