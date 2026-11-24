package ai.pipestream.sync;

import ai.pipestream.sync.v1.Asset;
import ai.pipestream.sync.v1.AssetPhase;
import ai.pipestream.sync.v1.AssetSyncStatus;
import ai.pipestream.sync.v1.GetAssetRequest;
import ai.pipestream.sync.v1.ListAssetsRequest;
import ai.pipestream.sync.v1.ListAssetsResponse;
import ai.pipestream.sync.v1.ReconcileRequest;
import ai.pipestream.sync.v1.ReconcileScopeRequest;
import ai.pipestream.sync.v1.SyncTableServiceGrpc;
import ai.pipestream.sync.v1.UpsertAssetRequest;
import ai.pipestream.sync.v1.WatchRequest;
import ai.pipestream.sync.v1.WatchResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncTableGrpcServiceTest {

    private Server server;
    private ManagedChannel channel;
    private SyncTableServiceGrpc.SyncTableServiceBlockingStub stub;
    private SyncTableServiceGrpc.SyncTableServiceStub async;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new SyncTableGrpcService(new AssetStore()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = SyncTableServiceGrpc.newBlockingStub(channel);
        async = SyncTableServiceGrpc.newStub(channel);
    }

    @AfterEach
    void stop() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void upsertUpdateDeleteAndReconcile() {
        Asset first = stub.upsertAsset(UpsertAssetRequest.newBuilder()
                .setAsset(Asset.newBuilder()
                        .setAssetId("confluence:page:1")
                        .setSource("confluence")
                        .setNativeId("1")
                        .setKind("page")
                        .setTitle("Design")
                        .setRunId("run-a")
                        .setAttachment(false))
                .build()).getAsset();
        assertThat(first.getPhase()).isEqualTo(AssetPhase.ASSET_PHASE_INITIAL_CRAWL);
        assertThat(first.getStatus()).isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);

        Asset updated = stub.upsertAsset(UpsertAssetRequest.newBuilder()
                .setAsset(first.toBuilder().setTitle("Design v2").setRunId("run-b"))
                .build()).getAsset();
        assertThat(updated.getPhase()).isEqualTo(AssetPhase.ASSET_PHASE_UPDATE);
        assertThat(updated.getFirstSeenAt()).isEqualTo(first.getFirstSeenAt());

        stub.upsertAsset(UpsertAssetRequest.newBuilder()
                .setAsset(Asset.newBuilder()
                        .setAssetId("confluence:attachment:a1")
                        .setSource("confluence")
                        .setKind("attachment")
                        .setNativeId("a1")
                        .setAttachment(true)
                        .setParentAssetId("confluence:page:1")
                        .setRunId("run-b")
                        .setTitle("notes.txt"))
                .build());

        List<ListAssetsResponse> attachments = new ArrayList<>();
        stub.listAssets(ListAssetsRequest.newBuilder()
                .setSource("confluence")
                .setAttachmentsOnly(true)
                .build()).forEachRemaining(attachments::add);
        assertThat(attachments).extracting(r -> r.getAsset().getAssetId())
                .containsExactly("confluence:attachment:a1");

        int deleted = stub.reconcile(ReconcileRequest.newBuilder()
                .setSource("confluence")
                .setRunId("run-b")
                .build()).getDeleted();
        assertThat(deleted).isZero();

        stub.upsertAsset(UpsertAssetRequest.newBuilder()
                .setAsset(Asset.newBuilder()
                        .setAssetId("confluence:page:gone")
                        .setSource("confluence")
                        .setKind("page")
                        .setNativeId("gone")
                        .setRunId("run-old"))
                .build());
        assertThat(stub.reconcile(ReconcileRequest.newBuilder()
                .setSource("confluence")
                .setRunId("run-b")
                .build()).getDeleted()).isEqualTo(1);
        assertThat(stub.getAsset(GetAssetRequest.newBuilder()
                .setAssetId("confluence:page:gone").build()).getAsset().getStatus())
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_DELETED);
    }

    /**
     * One drive of two was read again from scratch; the other was read
     * incrementally. A reconcile that names the first drive removes that
     * drive's unseen rows and nothing else.
     */
    @Test
    void reconcileOfOneScopeLeavesEveryOtherScopeAlone() {
        upsert("microsoft:c1:drive_item:a-kept", "c1", "drive_item", "drive-a", "run-2");
        upsert("microsoft:c1:drive_item:a-gone", "c1", "drive_item", "drive-a", "run-1");
        upsert("microsoft:c1:drive_item:b-unchanged", "c1", "drive_item", "drive-b", "run-1");
        upsert("microsoft:c1:drive_item:unscoped", "c1", "drive_item", "", "run-1");
        upsert("microsoft:c1:drive:drive-a", "c1", "drive", "drive-a", "run-1");
        upsert("microsoft:c2:drive_item:a-other", "c2", "drive_item", "drive-a", "run-1");

        int deleted = stub.reconcileScope(ReconcileScopeRequest.newBuilder()
                .setSource("microsoft")
                .setConnectionId("c1")
                .setKind("drive_item")
                .setScope("drive-a")
                .setRunId("run-2")
                .build()).getDeleted();

        assertThat(deleted).isEqualTo(1);
        assertThat(status("microsoft:c1:drive_item:a-gone"))
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_DELETED);
        assertThat(status("microsoft:c1:drive_item:a-kept"))
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
        // Another drive, a row that never said its scope, another kind and
        // another connection are all outside the reconcile.
        assertThat(status("microsoft:c1:drive_item:b-unchanged"))
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
        assertThat(status("microsoft:c1:drive_item:unscoped"))
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
        assertThat(status("microsoft:c1:drive:drive-a"))
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
        assertThat(status("microsoft:c2:drive_item:a-other"))
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
    }

    @Test
    void reconcileScopeWithoutAScopeIsRefusedAndTouchesNothing() {
        upsert("microsoft:c1:drive_item:a-old", "c1", "drive_item", "drive-a", "run-1");

        assertThatThrownBy(() -> stub.reconcileScope(ReconcileScopeRequest.newBuilder()
                .setSource("microsoft")
                .setConnectionId("c1")
                .setRunId("run-2")
                .build()))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .satisfies(e -> assertThat(((io.grpc.StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT))
                .hasMessageContaining("scope is required");
        assertThat(status("microsoft:c1:drive_item:a-old"))
                .isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_SYNCED);
    }

    private void upsert(String assetId, String connectionId, String kind, String scope,
            String runId) {
        stub.upsertAsset(UpsertAssetRequest.newBuilder()
                .setAsset(Asset.newBuilder()
                        .setAssetId(assetId)
                        .setSource("microsoft")
                        .setConnectionId(connectionId)
                        .setKind(kind)
                        .setScope(scope)
                        .setRunId(runId))
                .build());
    }

    private AssetSyncStatus status(String assetId) {
        return stub.getAsset(GetAssetRequest.newBuilder().setAssetId(assetId).build())
                .getAsset().getStatus();
    }

    @Test
    void watchStreamsMutations() throws Exception {
        CopyOnWriteArrayList<Asset> seen = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        async.watch(WatchRequest.newBuilder().setIncludeSnapshot(true).build(),
                new io.grpc.stub.StreamObserver<>() {
                    @Override
                    public void onNext(WatchResponse value) {
                        seen.add(value.getAsset());
                        first.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        stub.upsertAsset(UpsertAssetRequest.newBuilder()
                .setAsset(Asset.newBuilder()
                        .setAssetId("microsoft:drive_item:f1")
                        .setSource("microsoft")
                        .setKind("drive_item")
                        .setNativeId("f1")
                        .setAttachment(true)
                        .setTitle("file.txt"))
                .build());
        assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).extracting(Asset::getAssetId).contains("microsoft:drive_item:f1");
    }

    @Test
    void checkpointsAndMissingAsset() {
        stub.putCheckpoint(ai.pipestream.sync.v1.PutCheckpointRequest.newBuilder()
                .setCheckpoint(ai.pipestream.sync.v1.Checkpoint.newBuilder()
                        .setSource("microsoft")
                        .setScope("drive-1")
                        .setCursor("cursor-9"))
                .build());
        assertThat(stub.getCheckpoint(ai.pipestream.sync.v1.GetCheckpointRequest.newBuilder()
                .setSource("microsoft")
                .setScope("drive-1")
                .build()).getCheckpoint().getCursor()).isEqualTo("cursor-9");
        assertThatThrownBy(() -> stub.getAsset(GetAssetRequest.newBuilder()
                .setAssetId("missing").build()))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .hasMessageContaining("NOT_FOUND");
        Asset deleted = stub.deleteAsset(ai.pipestream.sync.v1.DeleteAssetRequest.newBuilder()
                .setAssetId("microsoft:drive_item:gone")
                .setRunId("run-x")
                .build()).getAsset();
        assertThat(deleted.getStatus()).isEqualTo(AssetSyncStatus.ASSET_SYNC_STATUS_DELETED);
        assertThat(deleted.getPhase()).isEqualTo(AssetPhase.ASSET_PHASE_DELETE);
    }
}
