import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.logbuffer.FragmentHandler;
import java.util.function.ToLongFunction;

public final class Transport {
    public long send(byte[] payload, ToLongFunction<byte[]> offer, int attempts, Runnable idle) {
        if (attempts <= 0) throw new IllegalArgumentException("positive attempt budget required");
        long result = Publication.NOT_CONNECTED;
        for (int i = 0; i < attempts; i++) {
            result = offer.applyAsLong(payload);
            if (result >= 0) return result;
            if (result != Publication.NOT_CONNECTED && result != Publication.BACK_PRESSURED &&
                result != Publication.CLOSED) return result;
            if (i + 1 < attempts) idle.run();
        }
        return result;
    }

    public FragmentHandler receiver(FragmentHandler completeMessage) {
        return new FragmentAssembler(completeMessage);
    }
}
