import io.aeron.*;
import io.aeron.cluster.*;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.*;
import io.aeron.logbuffer.Header;
import org.agrona.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class RecoveryService implements ClusteredService {
    final RequestWork state=new RequestWork();
    final int node,generation;final String mode,root;final ConsensusModule.Context context;
    Cluster cluster;
    RecoveryService(int node,int generation,String mode,ConsensusModule.Context context,String root) {
        this.node=node;this.generation=generation;this.mode=mode;this.context=context;this.root=root;
    }
    static String digest(String s) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new RuntimeException(e);}
    }
    void stateEvent(String event,long id) {
        System.out.println("{\"event\":\""+event+"\",\"node\":"+node+",\"id\":"+id+",\"count\":"+state.count+",\"total\":"+state.total+",\"config\":"+state.config+",\"tick\":"+state.tick+",\"timerId\":"+state.timerId+",\"timerDeadline\":"+state.timerDeadline+",\"dedup\":"+state.results.size()+",\"digest\":\""+digest(state.canonical())+"\",\"position\":"+cluster.logPosition()+"}");
    }
    public void onStart(Cluster c,Image image) {
        cluster=c;
        if(image!=null) {
            java.util.ArrayList<byte[]> frames=new java.util.ArrayList<>();
            FragmentAssembler assembler=new FragmentAssembler((b,o,l,h)->{byte[] a=new byte[l];b.getBytes(o,a);frames.add(a);});
            long end=System.nanoTime()+20_000_000_000L;
            while(!image.isEndOfStream()) {
                if(System.nanoTime()>end)throw new IllegalStateException("snapshot load watchdog");
                c.idleStrategy().idle(image.poll(assembler,10));
            }
            if(frames.size()!=1)throw new IllegalArgumentException("snapshot needs one assembled application record");
            byte[] data=frames.get(0);
            state.restore(data);stateEvent("LOADED",0);
        } else stateEvent("EMPTY_START",0);
        System.out.println("{\"event\":\"STARTED\",\"node\":"+node+"}");
    }
    public void onSessionMessage(ClientSession session,long t,DirectBuffer b,int o,int l,Header h) {
        if(l!=40)throw new IllegalArgumentException("wire length");
        long op=b.getLong(o),client=b.getLong(o+8),id=b.getLong(o+16),v=b.getLong(o+24),aux=b.getLong(o+32);
        if(cluster.logPosition()>context.commitPositionCounter().get())throw new IllegalStateException("beyond commit");
        RequestWork.Result r=state.apply(op,client,id,v,aux);
        stateEvent(op==9?"BARRIER":"APPLY",id);
        if(session!=null) {
            ExpandableArrayBuffer out=new ExpandableArrayBuffer();out.putLong(0,id);out.putLong(8,r.count());out.putLong(16,r.total());
            long end=System.nanoTime()+10_000_000_000L;cluster.idleStrategy().reset();
            while(session.offer(out,0,24)<0) {
                if(System.nanoTime()>end)throw new IllegalStateException("egress watchdog");cluster.idleStrategy().idle();
            }
        }
    }
    public void onTakeSnapshot(ExclusivePublication p) {
        stateEvent("SNAPSHOT_BEGIN",0);
        long end=System.nanoTime()+20_000_000_000L;
        while(mode.equals("slow-snapshot") && !Files.exists(Path.of(root,"release-snapshot"))) {
            if(System.nanoTime()>end)throw new IllegalStateException("snapshot pause watchdog");cluster.idleStrategy().idle();
        }
        byte[] bytes=state.snapshot();org.agrona.concurrent.UnsafeBuffer b=new org.agrona.concurrent.UnsafeBuffer(bytes);
        while(p.offer(b,0,bytes.length)<0) {
            if(System.nanoTime()>end)throw new IllegalStateException("snapshot write watchdog");cluster.idleStrategy().idle();
        }
        stateEvent("SNAPSHOT_WRITTEN",0);
    }
    public void onSessionOpen(ClientSession s,long t){}
    public void onSessionClose(ClientSession s,long t,CloseReason r){System.out.println("{\"event\":\"SESSION_CLOSED\",\"reason\":\""+r+"\"}");}
    public void onTimerEvent(long id,long t){}
    public void onRoleChange(Cluster.Role r){System.out.println("{\"event\":\"ROLE\",\"role\":\""+r+"\",\"node\":"+node+"}");}
    public void onTerminate(Cluster c){}
}
