import io.aeron.*;
import io.aeron.driver.*;
import io.aeron.logbuffer.*;
import org.agrona.concurrent.UnsafeBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

public final class UdpCheck {
    static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
    static void await(BooleanSupplier b,String s){long end=System.nanoTime()+12_000_000_000L;while(!b.getAsBoolean()){
        if(System.nanoTime()>end)throw new RuntimeException("INFRA deadline: "+s);LockSupport.parkNanos(100_000);}}
    static void event(String s){System.out.println("{\"event\":\""+s+"\"}");System.out.flush();}
    static byte[] payload(int id,int size){byte[] b=new byte[size];for(int i=0;i<size;i++)b[i]=(byte)(id*17+i*31);b[0]=(byte)id;return b;}
    static int size(int id){return id==2?4097:14;}
    static void write(Path p,String v){try {Path t=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(t,v);Files.move(t,p,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new RuntimeException(e);}}
    static String read(Path p){try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}
    public static void main(String[] a){try{
        switch(a[0]){case "policy":policy();break;case "geometry":geometry();break;case "sender":sender(a);break;case "receiver":receiver(a);break;default:throw new IllegalArgumentException("mode");}
        event("DONE");
    }catch(AssertionError e){System.out.println("MISS: "+e.getMessage());System.exit(1);}catch(Throwable e){e.printStackTrace();System.exit(2);}}
    static void policy(){Transport w=new Transport();
        long[][] sequences={{-1,-2,-3,64},{-4,64},{-5,64},{-2,-2,-2},{-9,64},{64},{-3,128}};
        long[] expected={64,-4,-5,-2,-9,64,128};int[] counts={4,1,1,3,1,1,2};
        for(int k=0;k<sequences.length;k++){final long[] tape=sequences[k];byte[] original=payload(19,14), message=original.clone();int[] calls={0},idles={0};
            long rc=w.send(message,b->{require(Arrays.equals(b,original),"retain rejected payload byte-for-byte");
                require(calls[0]<tape.length,"stop at terminal result or budget");return tape[calls[0]++];},tape.length,()->idles[0]++);
            require(rc==expected[k] && calls[0]==counts[k],"retry temporary codes only; expected rc="+expected[k]+" calls="+counts[k]+", observed rc="+rc+" calls="+calls[0]);
            require(idles[0]==counts[k]-1,"idle only between retries");require(Arrays.equals(message,original),"caller still owns unchanged payload");
        }
        boolean invalid=false;try{w.send(new byte[1],b->64,0,()->{});}catch(IllegalArgumentException e){invalid=true;}
        require(invalid,"reject zero attempt budget");event("POLICY_EXACT");
    }
    static void geometry(){
        require(FrameDescriptor.FRAME_ALIGNMENT==32 && io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH==32,"header/alignment=32");
        require(org.agrona.BitUtil.align(32+14,32)==64,"14-byte payload advances 64");
        for(int n:new int[]{64,1408,65504}) Configuration.validateMtuLength(n);
        for(int n:new int[]{32,1409,65536}){boolean bad=false;try{Configuration.validateMtuLength(n);}catch(RuntimeException e){bad=true;}require(bad,"reject invalid MTU "+n);}
        for(int n:new int[]{65536,1073741824})LogBufferDescriptor.checkTermLength(n);
        for(int n:new int[]{32768,98304}){boolean bad=false;try{LogBufferDescriptor.checkTermLength(n);}catch(RuntimeException e){bad=true;}require(bad,"reject invalid term "+n);}
        event("GEOMETRY_EXACT");
    }
    static void sender(String[] a){Path dir=Path.of(a[1]),gate=dir.resolve("receiver.ready"),done=dir.resolve("sender.done"),ack=dir.resolve("receiver.done");
        String channel="aeron:udp?endpoint=127.0.0.1:"+a[2]+"|mtu=1408|term-length=65536";
        try(MediaDriver driver=MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(dir.resolve("driver").toString())
                .dirDeleteOnStart(false).dirDeleteOnShutdown(true).socketRcvbufLength(0).threadingMode(ThreadingMode.SHARED));
            Aeron aeron=Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
            ExclusivePublication pub=aeron.addExclusivePublication(channel,1002)){
            Transport w=new Transport();boolean[] first={true};long[] accepted={0};
            for(int id=0;id<5;id++){
                byte[] bytes=payload(id,size(id));
                long rc=w.send(bytes,b->{long r=pub.offer(new UnsafeBuffer(b));
                    if(first[0]){require(r==Publication.NOT_CONNECTED,"sender-before-subscriber barrier must reject first offer");first[0]=false;event("REJECTED");}
                    return r;},20000,()->{
                        await(()->Files.exists(gate),"subscriber readiness barrier");
                        await(pub::isConnected,"UDP connection");LockSupport.parkNanos(100_000);
                    });
                require(rc>0,"retry rejected payload; don't drop or report negative as sent");accepted[0]=rc;
            }
            boolean oversize=false;try{pub.tryClaim(pub.maxPayloadLength()+1,new BufferClaim());}catch(IllegalArgumentException e){oversize=true;}
            require(oversize,"oversized tryClaim cannot fragment");
            write(done,Long.toString(accepted[0]));event("SENT");await(()->Files.exists(ack),"receiver exact drain acknowledgment");
            require(read(ack).equals("HIT"),"receiver detected byte/count mismatch");
        }
    }
    static void receiver(String[] a){Path dir=Path.of(a[1]);String channel="aeron:udp?endpoint=127.0.0.1:"+a[2];
        try(Aeron aeron=Aeron.connect(new Aeron.Context().aeronDirectoryName(dir.resolve("driver").toString()));
            Subscription sub=aeron.addSubscription(channel,1002)){
            List<byte[]> got=new ArrayList<>();FragmentHandler handler=new Transport().receiver((b,o,n,h)->{byte[] v=new byte[n];b.getBytes(o,v);got.add(v);});
            await(sub::isConnected,"receiver image attached before first send");
            write(dir.resolve("receiver.ready"),"ready");event("READY");
            Path done=dir.resolve("sender.done");await(()->{
                sub.poll(handler,64);return Files.exists(done) && sub.imageCount()==1 && sub.imageAtIndex(0).position()>=Long.parseLong(read(done));
            },"UDP final-position drain");
            boolean equal=got.size()==5;
            if(equal)for(int i=0;i<5;i++)equal &= Arrays.equals(got.get(i),payload(i,size(i)));
            write(dir.resolve("receiver.done"),equal?"HIT":"MISS");
            int badMessage=-1,badByte=-1;String expectedValue="5 messages",actualValue=got.size()+" messages";
            if(got.size()==5)for(int i=0;i<5 && badMessage<0;i++){
                byte[] want=payload(i,size(i)),have=got.get(i);
                if(have.length!=want.length){badMessage=i;expectedValue="length "+want.length;actualValue="length "+have.length;}
                else for(int j=0;j<want.length;j++)if(have[j]!=want[j]){badMessage=i;badByte=j;expectedValue="byte "+want[j];actualValue="byte "+have[j];break;}
            }
            require(equal,"assemble full messages and retain rejected ID 0; received "+got.size()+" messages; message position="+badMessage+" byte position="+badByte+" expected="+expectedValue+" actual="+actualValue);
            System.out.println("{\"event\":\"EXACT\",\"ids\":[0,1,2,3,4],\"lengths\":[14,14,4097,14,14]}");
        }
    }
}
