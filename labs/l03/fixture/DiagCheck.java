import io.aeron.*;
import io.aeron.driver.*;
import io.aeron.driver.ext.*;
import io.aeron.driver.status.*;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.samples.CncFileReader;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class DiagCheck {
    static final int STREAM=1003, SIZE=1376;
    static final Diagnostics work=new Diagnostics();
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static Runnable pump=()->{};
    static void await(BooleanSupplier predicate,String why){long end=System.nanoTime()+15_000_000_000L;
        while(true){pump.run();if(predicate.getAsBoolean())return;if(System.nanoTime()>end)throw new RuntimeException("INFRA watchdog: "+why);LockSupport.parkNanos(100_000);}}
    static void event(String s){System.out.println("{\"event\":\""+s+"\"}");System.out.flush();}
    static byte[] bytes(int id){byte[] b=new byte[SIZE];for(int i=0;i<b.length;i++)b[i]=(byte)(id*17+i*31);new UnsafeBuffer(b).putInt(0,id);return b;}
    static MediaDriver.Context context(Path dir){return new MediaDriver.Context().aeronDirectoryName(dir.toString())
        .dirDeleteOnStart(false).dirDeleteOnShutdown(false).socketRcvbufLength(0).threadingMode(ThreadingMode.SHARED);}
    static long counter(Aeron a,int type,int session){long[] value={-1};a.countersReader().forEach((id,t,key,label)->{
        if(t==type && key.getInt(StreamCounter.SESSION_ID_OFFSET)==session && key.getInt(StreamCounter.STREAM_ID_OFFSET)==STREAM)
            value[0]=a.countersReader().getCounterValue(id);});return value[0];}
    static long system(Aeron a,String prefix){long[] value={-1};a.countersReader().forEach((id,t,key,label)->{
        if(t==0 && label.startsWith(prefix))value[0]=a.countersReader().getCounterValue(id);});return value[0];}
    static void snapshot(Aeron a,Publication p,Subscription s,String stage){
        System.out.println("{\"event\":\"POSITIONS\",\"stage\":\""+stage+"\",\"session\":"+p.sessionId()+",\"stream\":"+STREAM+
            ",\"hwm\":"+counter(a,ReceiverHwm.RECEIVER_HWM_TYPE_ID,p.sessionId())+",\"rebuilt\":"+counter(a,ReceiverPos.RECEIVER_POS_TYPE_ID,p.sessionId())+
            ",\"consumed\":"+s.imageAtIndex(0).position()+",\"offered\":"+p.position()+",\"channel\":\""+p.channel()+"\"}");}
    static void diagnose(Aeron a,Publication p,Subscription s,String expected){
        long h=counter(a,ReceiverHwm.RECEIVER_HWM_TYPE_ID,p.sessionId()),r=counter(a,ReceiverPos.RECEIVER_POS_TYPE_ID,p.sessionId()),c=s.imageAtIndex(0).position();
        require(work.diagnose(h,r,c,0,1200).equals(expected),"diagnose "+expected+": compare same-image hwm/rebuilt/consumed");
        require(work.lag(r,c)==r-c,"lag is rebuilt minus consumed, not hwm minus rebuilt");}
    static final class DropOnce implements LossGenerator {
        volatile int drops;
        public boolean shouldDropFrame(InetSocketAddress a,UnsafeBuffer b,int n){return false;}
        public boolean shouldDropFrame(InetSocketAddress a,UnsafeBuffer b,int stream,int session,int term,int offset,int n){
            if(stream==STREAM && offset==1408 && n>32 && drops==0){drops++;event("DROPPED_DATA_ONCE");return true;}return false;}
    }
    static void live(Path root,int port,String mode)throws Exception {
        DropOnce loss=new DropOnce();MediaDriver.Context ctx=context(root.resolve("driver"));
        if(mode.equals("gap"))ctx.receiveChannelEndpointSupplier((u,d,s,c)->new DebugReceiveChannelEndpoint(u,d,s,c,loss,(a,b,n)->false));
        String channel="aeron:udp?endpoint=127.0.0.1:"+port+"|term-length=65536|mtu=1408";
        try(MediaDriver driver=MediaDriver.launch(ctx);Aeron aeron=Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
            Subscription sub=aeron.addSubscription(channel+"|nak-delay=500ms",STREAM);
            ExclusivePublication pub=aeron.addExclusivePublication(channel,STREAM)){
            await(()->pub.isConnected()&&sub.isConnected(),"image ready");event("IMAGE_AVAILABLE");
            List<byte[]> received=new ArrayList<>();FragmentHandler handler=(b,o,n,h)->{byte[] v=new byte[n];b.getBytes(o,v);received.add(v);};
            int sent=0;long last=0;boolean bp=false;
            int first=mode.equals("gap")?12:200;
            long end=System.nanoTime()+10_000_000_000L;
            while(sent<first){long rc=pub.offer(new UnsafeBuffer(bytes(sent)));if(rc>0){last=rc;sent++;}
                else if(rc==Publication.BACK_PRESSURED){
                    // Connection discovery can precede the first position-limit
                    // update. A transient -2 is not proof that the tape is full.
                    if(!mode.equals("gap") && sent>0){bp=true;break;}
                    LockSupport.parkNanos(100_000);
                }
                else if(rc==Publication.CLOSED || rc==Publication.MAX_POSITION_EXCEEDED)
                    throw new RuntimeException("INFRA terminal offer status "+rc);
                if(System.nanoTime()>end)throw new RuntimeException("INFRA initial offer watchdog");}
            if(mode.equals("gap")){
                require(sent==12,"initial gap tape fits window");
                await(()->loss.drops==1 && counter(aeron,ReceiverHwm.RECEIVER_HWM_TYPE_ID,pub.sessionId())>counter(aeron,ReceiverPos.RECEIVER_POS_TYPE_ID,pub.sessionId()),"observable gap");
                snapshot(aeron,pub,sub,"gap");diagnose(aeron,pub,sub,"GAP");
                await(()->system(aeron,"Retransmits sent")>0 && system(aeron,"NAKs sent")>0,"NAK/retransmit");
                require(loss.drops==1,"drop exactly once; preserve control/retransmission");
            }else{
                require(bp && sent>0,"paused consumer must produce BACK_PRESSURED");
                await(()->counter(aeron,ReceiverPos.RECEIVER_POS_TYPE_ID,pub.sessionId())>0 &&
                    counter(aeron,ReceiverHwm.RECEIVER_HWM_TYPE_ID,pub.sessionId())==counter(aeron,ReceiverPos.RECEIVER_POS_TYPE_ID,pub.sessionId()),"rebuilt backlog");
                snapshot(aeron,pub,sub,"lag");diagnose(aeron,pub,sub,"SLOW_CONSUMER");event("BACK_PRESSURED");
            }
            require(work.resumePolling(),"repair requires resuming the paused poll loop");
            // An ADMIN_ACTION can append term padding after the last message.
            // Drain the whole published stream, including that padding; polling
            // can skip directly past the last successful message's position.
            final long offered=pub.position();
            System.out.println("{\"event\":\"DRAIN_TARGET\",\"lastMessage\":"+last+",\"stream\":"+offered+"}");
            await(()->{sub.poll(handler,64);return sub.imageAtIndex(0).position()==offered;},"known final-position drain");
            require(received.size()==sent,"exact count after drain");
            for(int i=0;i<sent;i++)require(Arrays.equals(received.get(i),bytes(i)),"exact ordered payload ID "+i);
            snapshot(aeron,pub,sub,"drained");diagnose(aeron,pub,sub,"DRAINED");
            if(mode.equals("lag")){
                final int next=sent;long[] rc={-1};await(()->{rc[0]=pub.offer(new UnsafeBuffer(bytes(next)));return rc[0]>0;},"publication resumes after drain");
                await(()->{sub.poll(handler,64);return sub.imageAtIndex(0).position()==rc[0];},"post-repair progress");
                require(received.size()==sent+1&&Arrays.equals(received.get(sent),bytes(sent)),"post-repair exact bytes");
            }
            event("EXACT_DRAIN");
            if(mode.equals("gap")){event("INSPECT_READY");await(()->Files.exists(root.resolve("inspected")),"bounded live inspection barrier");}
        }
    }
    static void liveness(Path root){
        String dir=root.resolve("driver").toString();System.setProperty("aeron.dir",dir);
        try(MediaDriver d=MediaDriver.launch(context(Path.of(dir)).threadingMode(ThreadingMode.INVOKER));CncFileReader cnc=CncFileReader.map()){
            long initial=cnc.driverHeartbeatMs();await(()->{d.sharedAgentInvoker().invoke();return cnc.driverHeartbeatMs()>initial;},"initial driver heartbeat");
            await(()->cnc.driverHeartbeatAgeMs()>1300,"stale heartbeat during deliberate driver duty omission");
            require(work.diagnose(0,0,0,cnc.driverHeartbeatAgeMs(),1200).equals("STALE_DRIVER"),"stale CnC is not DRAINED");event("STALE_DRIVER");
            long old=cnc.driverHeartbeatMs();work.duty(d.sharedAgentInvoker());
            require(cnc.driverHeartbeatMs()>old,"restore invoker duty to refresh heartbeat");
            require(work.diagnose(0,0,0,cnc.driverHeartbeatAgeMs(),1200).equals("DRAINED"),"fresh heartbeat recovery");event("HEARTBEAT_RECOVERED");
        }
    }
    static void invoker(Path root){
        try(MediaDriver d=MediaDriver.launch(context(root.resolve("driver")));
            Aeron a=Aeron.connect(new Aeron.Context().aeronDirectoryName(d.aeronDirectoryName()).useConductorAgentInvoker(true));
            Aeron producer=Aeron.connect(new Aeron.Context().aeronDirectoryName(d.aeronDirectoryName()));
            Subscription sub=a.addSubscription("aeron:ipc",STREAM);
            ExclusivePublication pub=producer.addExclusivePublication("aeron:ipc?term-length=65536",STREAM)){
            await(pub::isConnected,"driver has linked subscriber");
            require(sub.imageCount()==0,"image callback remains pending without client duty");
            event("CONDUCTOR_IMAGE_PENDING");
            long end=System.nanoTime()+1_000_000_000L;
            while(sub.imageCount()==0 && System.nanoTime()<end){work.duty(a.conductorAgentInvoker());LockSupport.parkNanos(100_000);}
            require(sub.imageCount()==1,"restore client conductor invocation to process image event");
            event("CONDUCTOR_RECOVERED");
        }
    }
    static void trace(){
        require(work.diagnose(128,64,64,0,1200).equals("GAP"),"gap trace");
        require(work.diagnose(128,128,32,0,1200).equals("SLOW_CONSUMER"),"lag trace");
        require(work.diagnose(-1,-1,0,0,1200).equals("UNKNOWN"),"missing counters are unknown");
        require(work.diagnose(128,64,32,1300,1200).equals("STALE_DRIVER"),"check liveness before stale positions");
        require(work.lag(128,32)==96,"lag arithmetic");
        long[] windows={600,200,400};boolean[] tagged={true,false,true};
        require(work.limit("max",windows,tagged)==600,"max model chooses fastest");
        require(work.limit("min",windows,tagged)==200,"min model chooses slowest");
        require(work.limit("tagged",windows,tagged)==400,"tagged model ignores nonmembers");
        require(work.needsReplay(10,13)&&!work.needsReplay(10,10),"unreliable/untethered gap trace requires application recovery");
        require(ReceiverHwm.RECEIVER_HWM_TYPE_ID==3&&ReceiverPos.RECEIVER_POS_TYPE_ID==5&&SubscriberPos.SUBSCRIBER_POSITION_TYPE_ID==4,"release counter schema");
        event("TRACE_MODEL_AND_SCHEMA");
    }
    static void late(Path root){
        try(MediaDriver d=MediaDriver.launch(context(root.resolve("driver")).threadingMode(ThreadingMode.INVOKER));Aeron a=Aeron.connect(new Aeron.Context().aeronDirectoryName(d.aeronDirectoryName()).useConductorAgentInvoker(true).driverAgentInvoker(d.sharedAgentInvoker()));
            Subscription anchor=a.addSubscription("aeron:ipc?term-length=65536",STREAM);
            ExclusivePublication p=a.addExclusivePublication("aeron:ipc?term-length=65536",STREAM)){
            pump=()->{d.sharedAgentInvoker().invoke();a.conductorAgentInvoker().invoke();};
            await(()->p.isConnected()&&anchor.isConnected(),"anchor");
            for(int id=0;id<3;id++){final int n=id;await(()->p.offer(new UnsafeBuffer(bytes(n)))>0,"prefix offer");}
            await(()->{anchor.poll((b,o,n,h)->{},64);return anchor.imageAtIndex(0).position()==p.position();},"anchor prefix");pump.run();
            try(Subscription late=a.addSubscription("aeron:ipc?term-length=65536",STREAM)){
                await(late::isConnected,"late image");long join=late.imageAtIndex(0).position();require(join==p.position()&&join>0,"late join at current position");
                int[] got={-1};await(()->p.offer(new UnsafeBuffer(bytes(3)))>0,"suffix offer");
                await(()->{late.poll((b,o,n,h)->got[0]=b.getInt(o),64);return got[0]>=0;},"late suffix");
                require(got[0]==3 && work.needsReplay(0,got[0]),"late join needs replay for missing prefix");
            }
            await(()->p.offer(new UnsafeBuffer(bytes(4)))>0,"closed interval");
            await(()->{anchor.poll((b,o,n,h)->{},64);return anchor.imageAtIndex(0).position()==p.position();},"anchor closed interval");pump.run();
            try(Subscription reopened=a.addSubscription("aeron:ipc?term-length=65536",STREAM);
                ExclusivePublication other=a.addExclusivePublication("aeron:ipc?term-length=65536",STREAM)){
                await(()->reopened.imageCount()==2,"two independently identified images");
                require(p.sessionId()!=other.sessionId(),"independent sessions");
                int[] got={-1};await(()->p.offer(new UnsafeBuffer(bytes(5)))>0,"reopen suffix");
                await(()->{reopened.poll((b,o,n,h)->{if(h.sessionId()==p.sessionId())got[0]=b.getInt(o);},64);return got[0]>=0;},"reopen message");
                require(got[0]==5&&work.needsReplay(4,got[0]),"reopening misses closed interval");
                event("LATE_REOPEN_TWO_IMAGES");
            }
        }
    }
    public static void main(String[] args){try{
        Path root=Path.of(args[1]);switch(args[0]){case "gap":case "lag":live(root,Integer.parseInt(args[2]),args[0]);break;
            case "liveness":liveness(root);break;case "invoker":invoker(root);break;case "late":late(root);break;case "trace":trace();break;default:throw new IllegalArgumentException("mode");}
        event("DONE");
    }catch(AssertionError e){System.out.println("MISS: "+e.getMessage());System.exit(1);}catch(Throwable e){e.printStackTrace();System.exit(2);}}
}
