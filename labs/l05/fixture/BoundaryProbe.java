import io.aeron.*;
import io.aeron.driver.*;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.concurrent.UnsafeBuffer;
import java.util.*;
public final class BoundaryProbe {
    static void require(boolean ok,String why) { if(!ok)throw new IllegalArgumentException("MISS: "+why); }
    public static void main(String[] args) throws Exception {
        StateWork work=new StateWork();
        try(MediaDriver d=MediaDriver.launchEmbedded(new MediaDriver.Context().aeronDirectoryName(args[0]).threadingMode(ThreadingMode.SHARED).dirDeleteOnStart(true).dirDeleteOnShutdown(true));
            Aeron a=Aeron.connect(new Aeron.Context().aeronDirectoryName(d.aeronDirectoryName()));
            Subscription s=a.addSubscription("aeron:ipc?term-length=64k",55);
            ExclusivePublication p=a.addExclusivePublication("aeron:ipc?term-length=64k",55)) {
            long end=System.nanoTime()+5_000_000_000L;
            while(!p.isConnected() || s.imageCount()!=1){if(System.nanoTime()>end)throw new IllegalStateException("connect watchdog");Thread.sleep(1);}
            UnsafeBuffer b=new UnsafeBuffer(new byte[8]);b.putLong(0,101);long first;
            while((first=p.offer(b,0,8))<0){if(System.nanoTime()>end)throw new IllegalStateException("offer watchdog");Thread.sleep(1);}
            b.putLong(0,202);long second;
            while((second=p.offer(b,0,8))<0){if(System.nanoTime()>end)throw new IllegalStateException("offer watchdog");Thread.sleep(1);}
            Image image=s.imageAtIndex(0);List<Long> got=new ArrayList<>();
            image.boundedControlledPoll((buf,o,l,h)->{got.add(buf.getLong(o));return Action.CONTINUE;},work.commitLimit(first,second),10);
            require(got.equals(List.of(101L)),"commit fence: first poll must exclude appended second record");
            image.boundedControlledPoll((buf,o,l,h)->{got.add(buf.getLong(o));return Action.CONTINUE;},work.commitLimit(second,second),10);
            require(got.equals(List.of(101L,202L)),"commit advance must release second record");
            System.out.println("{\"event\":\"BOUNDARY_PASS\",\"first\":"+first+",\"second\":"+second+"}");
        }
    }
}
