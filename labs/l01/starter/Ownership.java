import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;

public final class Ownership {
    private final Thread owner = Thread.currentThread();

    public void guard(int length, int maxPayload) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("single owner required");
        if (length < 0 || length > maxPayload) throw new IllegalArgumentException("invalid claim length");
    }

    public void encode(BufferClaim claim, byte[] payload, boolean failAfterWrite) {
        boolean complete = false;
        try {
            claim.buffer().putBytes(claim.offset(), payload);
            if (failAfterWrite) throw new IllegalArgumentException("injected encoder failure");
            complete = true;
        } finally {
            resolve(claim, complete);
        }
    }

    public void resolve(BufferClaim claim, boolean complete) {
        // TODO: commit complete claims and abort failed ones (lesson 01 prepares
        // the completion or independent exercise over this file).
        if (complete) {}
        else {}
    }

    public Action consume(boolean downstreamReady, Runnable mutate) {
        if (!downstreamReady) return Action.ABORT;
        mutate.run();
        return Action.CONTINUE;
    }

    public void handle(boolean valid, Runnable mutate, Runnable reject) {
        if (!valid) { reject.run(); return; }
        mutate.run();
    }
}
