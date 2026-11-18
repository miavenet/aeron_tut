import io.aeron.ChannelUriStringBuilder;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;

import java.io.File;

/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from aeron-samples BasicAuctionClusteredServiceNode, tag 1.54.1.
 * Modified for this course: local configuration, service wiring,
 * lifecycle control, and fault scenarios.
 * Full license: ../../lib/LICENSE-aeron.txt
 */
public class ClusterNode
{
    static int PORT_BASE;

    static int port(final int nodeId, final int offset)
    {
        return PORT_BASE + (nodeId * 100) + offset;
    }

    static String clusterMembers(final int n)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
        {
            // id,ingress(client-facing),consensus(member-facing),log,catchup(transfer),archive
            sb.append(i)
                .append(",localhost:").append(port(i, 2))
                .append(",localhost:").append(port(i, 3))
                .append(",localhost:").append(port(i, 4))
                .append(",localhost:").append(port(i, 5))
                .append(",localhost:").append(port(i, 1))
                .append('|');
        }
        return sb.toString();
    }

    private static ErrorHandler errorHandler(final String ctx)
    {
        return (t) ->
        {
            System.err.println(ctx);
            t.printStackTrace(System.err);
        };
    }

    public static void main(final String[] args)
    {
        final int nodeId = Integer.parseInt(args[0]);
        PORT_BASE = Integer.parseInt(args[2]);
        final File baseDir = new File(args[1], "node" + nodeId);
        final String aeronDirName = new File(baseDir, "aeron").getAbsolutePath();

        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED).mtuLength(512)
            .termBufferSparseFile(true)
            .ipcTermBufferLength(64 * 1024).publicationTermBufferLength(64 * 1024)
            .dirDeleteOnStart(true).dirDeleteOnShutdown(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .errorHandler(errorHandler("Media Driver"));

        final AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
            .controlResponseChannel("aeron:udp?endpoint=localhost:0");

        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(baseDir, "archive"))
            .controlChannel(new ChannelUriStringBuilder().media("udp").termLength(64 * 1024)
                .endpoint("localhost:" + port(nodeId, 1)).build())
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel("aeron:ipc?term-length=64k")
            .recordingEventsEnabled(false)
            .segmentFileLength(128 * 1024)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .replicationChannel("aeron:udp?endpoint=localhost:0");

        if (args[4].equals("low-storage")) archiveContext.lowStorageSpaceThreshold(Long.MAX_VALUE);

        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirName);

        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .errorHandler(errorHandler("Consensus Module"))
            .clusterMemberId(nodeId)
            .clusterMembers(clusterMembers(3))
            .clusterDir(new File(baseDir, "cluster"))
            .ingressChannel("aeron:udp?term-length=64k")
            .logChannel("aeron:udp?term-length=64k")
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .archiveContext(aeronArchiveContext.clone());

        if (args[4].equals("session-limit")) consensusModuleContext.maxConcurrentSessions(1);
        consensusModuleContext.sessionTimeoutNs(args[4].equals("session-limit") ? 30_000_000_000L : 3_000_000_000L);

        final ClusteredServiceContainer.Context clusteredServiceContext = new ClusteredServiceContainer.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveContext(aeronArchiveContext.clone())
            .clusterDir(new File(baseDir, "cluster"))
            .clusteredService(new RecoveryService(nodeId, Integer.parseInt(args[3]), args[4], consensusModuleContext, args[1]))
            .errorHandler(errorHandler("Clustered Service"));

        if (args[4].equals("incompatible")) clusteredServiceContext.appVersion(65536);

        try (ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();
            ClusteredMediaDriver clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext.terminationHook(barrier::signalAll),
                archiveContext,
                consensusModuleContext.terminationHook(barrier::signalAll));
            ClusteredServiceContainer container = ClusteredServiceContainer.launch(
                clusteredServiceContext.terminationHook(barrier::signalAll)))
        {
            System.out.println("[node" + nodeId + "] started, pid=" + ProcessHandle.current().pid());
            while (!new File(args[1], "stop"+nodeId).exists()) {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
            System.out.println("[node" + nodeId + "] exiting");
        }
    }
}
