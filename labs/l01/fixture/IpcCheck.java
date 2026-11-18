import io.aeron.*;
import io.aeron.driver.*;
import io.aeron.logbuffer.*;
import org.agrona.concurrent.UnsafeBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

public final class IpcCheck {
    static void require(boolean b, String s) { if (!b) throw new AssertionError(s); }
    static void await(BooleanSupplier b, String s) {
        long end=System.nanoTime()+10_000_000_000L;
        while (!b.getAsBoolean()) {
            if (System.nanoTime()>end) throw new RuntimeException("INFRA deadline: "+s);
            LockSupport.parkNanos(100_000);
        }
    }
    static BufferClaim claim(ExclusivePublication p, int n) {
        BufferClaim c=new BufferClaim();
        await(()->p.tryClaim(n,c)>0,"claim space"); return c;
    }
    static void event(String name) { System.out.println("{\"event\":\"PASS\",\"criterion\":\""+name+"\"}"); }
    static void invalid(Runnable f, String s) {
        boolean caught=false; try { f.run(); } catch (IllegalArgumentException e) { caught=true; }
        require(caught,s);
    }
    static void resolved(BufferClaim c, boolean commit) {
        require(c.buffer().getInt(0,ByteOrder.LITTLE_ENDIAN)>0,"resolve claim: commit complete bytes or abort failure");
        require((c.headerType()==1)==commit,"failed encoder must abort, not publish partial bytes");
    }
    public static void main(String[] args) {
        try { run(args); System.out.println("{\"event\":\"DONE\",\"status\":\"HIT\"}"); }
        catch (AssertionError e) { System.out.println("MISS: "+e.getMessage()); System.exit(1); }
        catch (Throwable e) { e.printStackTrace(); System.exit(2); }
    }
    static void run(String[] args) throws Exception {
        String mode=args[1]; Ownership work=new Ownership(); List<Throwable> errors=new ArrayList<>();
        try (MediaDriver driver=MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(args[0])
                .dirDeleteOnStart(false).dirDeleteOnShutdown(true).threadingMode(ThreadingMode.SHARED)
                .ipcTermBufferLength(65536));
             Aeron aeron=Aeron.connect(new Aeron.Context().aeronDirectoryName(args[0]).errorHandler(errors::add));
             Subscription sub=aeron.addSubscription("aeron:ipc",1001);
             ExclusivePublication pub=aeron.addExclusivePublication("aeron:ipc",1001)) {
            await(()->pub.isConnected() && sub.isConnected(),"IPC image");
            Image image=sub.imageAtIndex(0);
            if (mode.equals("core") || mode.equals("owner")) {
                work.guard(0,pub.maxPayloadLength()); work.guard(pub.maxPayloadLength(),pub.maxPayloadLength());
                invalid(()->work.guard(-1,pub.maxPayloadLength()),"reject negative length before claiming");
                invalid(()->work.guard(pub.maxPayloadLength()+1,pub.maxPayloadLength()),"reject oversize before claiming");
                AtomicReference<Throwable> foreign=new AtomicReference<>();
                Thread t=new Thread(()->{try { work.guard(1,pub.maxPayloadLength()); } catch(Throwable e){ foreign.set(e); }});
                t.start(); t.join(2000); require(!t.isAlive(),"owner probe completed");
                require(foreign.get() instanceof IllegalStateException,"reject a second owner before touching publication");
                event("owner-length-guard");
                if(mode.equals("owner")) return;
                List<byte[]> expected=new ArrayList<>(), actual=new ArrayList<>();
                FragmentHandler receive=(b,o,n,h)->{byte[] v=new byte[n];b.getBytes(o,v);actual.add(v);};
                for(int i=0;i<5;i++) {
                    byte[] v=new byte[]{(byte)i,0,(byte)255,(byte)(31+i)}; expected.add(v);
                    BufferClaim c=claim(pub,v.length); work.encode(c,v,false); resolved(c,true);
                }
                BufferClaim failed=claim(pub,4); boolean threw=false;
                try { work.encode(failed,new byte[]{99,98,97,96},true); } catch(IllegalArgumentException e) { threw=true; }
                require(threw,"encoder must propagate failure"); resolved(failed,false);
                long end=pub.position(); await(()->{sub.poll(receive,20);return image.position()==end;},"exact drain");
                require(actual.size()==expected.size(),"abort contributes no message; exact count required");
                for(int i=0;i<expected.size();i++) require(Arrays.equals(actual.get(i),expected.get(i)),"exact ordered ID/payload at "+i);
                event("exact-bytes-ids-and-exception-abort");
                invalid(()->pub.tryClaim(pub.maxPayloadLength()+1,new BufferClaim()),"Aeron rejects oversize claim");
                invalid(()->pub.tryClaim(-1,new BufferClaim()),"Aeron rejects negative claim"); event("aeron-claim-length");
                long before=image.position(); BufferClaim held=claim(pub,4);
                BufferClaim next=claim(pub,4); byte[] tail={42,0,-1,7};work.encode(next,tail,false);resolved(next,true);
                int oldCount=actual.size();
                for(int i=0;i<100;i++) sub.poll(receive,20);
                require(image.position()==before && actual.size()==oldCount,"unresolved claim must block following frame");
                work.resolve(held,false);resolved(held,false);
                long finalPosition=pub.position();await(()->{sub.poll(receive,20);return image.position()==finalPosition;},"explicit abort releases tail");
                require(actual.size()==oldCount+1 && Arrays.equals(actual.get(oldCount),tail),"explicit resolution releases exact tail once");
                event("withheld-claim-blocks-until-explicit-resolution");
            } else if(mode.equals("controlled")) {
                BufferClaim c=claim(pub,1);c.buffer().putByte(c.offset(),(byte)7);c.commit();
                long before=image.position();int[] calls={0},mutations={0};
                sub.controlledPoll((b,o,n,h)->{calls[0]++;return work.consume(false,()->mutations[0]++);},1);
                require(calls[0]==1 && mutations[0]==0 && image.position()==before,"ABORT must not mutate or advance");
                sub.controlledPoll((b,o,n,h)->{calls[0]++;return work.consume(true,()->mutations[0]++);},1);
                require(calls[0]==2 && mutations[0]==1 && image.position()==pub.position(),"redelivery applies exactly once when admitted");
                event("controlled-redelivery-without-premature-mutation");
            } else if(mode.equals("handler")) {
                BufferClaim c=claim(pub,1);c.buffer().putByte(c.offset(),(byte)0);c.commit();
                int[] thrown={0};sub.poll((b,o,n,h)->{thrown[0]++;throw new IllegalArgumentException("probe");},1);
                sub.poll((b,o,n,h)->thrown[0]++,1);
                require(thrown[0]==1 && errors.size()==1 && image.position()==pub.position(),"ordinary poll reports exception and advances throwing frame");
                errors.clear();int[] applied={0},rejected={0};
                for(byte v:new byte[]{0,1}) {BufferClaim x=claim(pub,1);x.buffer().putByte(x.offset(),v);x.commit();}
                sub.poll((b,o,n,h)->work.handle(b.getByte(o)!=0,()->applied[0]++,()->rejected[0]++),10);
                require(applied[0]==1 && rejected[0]==1 && errors.isEmpty(),"validate before mutation; account rejected input without throwing");
                require(image.position()==pub.position(),"handler repair drains following record");
                event("ordinary-poll-error-and-explicit-rejection");
            } else throw new IllegalArgumentException("unknown mode");
            require(errors.isEmpty(),"unexpected Aeron error; inspect error path");
        }
    }
}
