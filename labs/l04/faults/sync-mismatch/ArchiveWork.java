import io.aeron.*;
import io.aeron.archive.Archive;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.status.RecordingPos;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.status.CountersReader;

public final class ArchiveWork {
    public boolean simulateSpies() { return true; }

    public void configure(Archive.Context context, String control) {
        context.controlChannel(control)
            .replicationChannel("aeron:udp?endpoint=127.0.0.1:0")
            .fileSyncLevel(1).catalogFileSyncLevel(0).deleteArchiveOnStart(false);
    }

    public long recordingId(CountersReader counters, int counter, long subscriptionId) {
        return RecordingPos.getRecordingId(counters, counter);
    }

    public boolean recordingComplete(long position, long target, boolean active) {
        return active && position >= target;
    }

    public boolean stopped(long catalogStop) { return catalogStop != AeronArchive.NULL_POSITION; }

    public long replayStart(long catalogStart, long catalogStop) { return catalogStart; }

    public FragmentHandler receiver(FragmentHandler complete) { return new FragmentAssembler(complete); }

    public AeronArchive.Context client(String directory, int responseStream) {
        return new AeronArchive.Context().aeronDirectoryName(directory)
            .controlRequestChannel("aeron:ipc").controlResponseChannel("aeron:ipc")
            .controlResponseStreamId(responseStream).messageTimeoutNs(5_000_000_000L);
    }

    public void requirePrivateMergeClient(boolean shared) {
        if (shared) throw new IllegalArgumentException("ReplayMerge requires a private client");
    }

    public boolean deadlineExpired(long now, long deadline) { return now >= deadline; }

    public String extensionChannel(long stop, int initialTerm, int term, int mtu) {
        return new ChannelUriStringBuilder("aeron:ipc").mtu(mtu)
            .initialPosition(stop, initialTerm, term).build();
    }

    public String verificationChecksum() { return "io.aeron.archive.checksum.Crc32"; }
    public boolean allowTruncate(boolean disposableCopy) { return disposableCopy; }
    public String descriptorCommand() { return "describe"; }
}
