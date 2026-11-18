import io.aeron.*;
import io.aeron.cluster.*;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.*;
import io.aeron.logbuffer.Header;
import org.agrona.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class StateService implements ClusteredService {
    private final StateWork state = new StateWork();
    private final int node, generation;
    private final String mode;
    private final ConsensusModule.Context context;
    private Cluster cluster;
    public StateService(int node, int generation, String mode, ConsensusModule.Context context) {
        this.node=node; this.generation=generation; this.mode=mode; this.context=context;
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public void onStart(Cluster cluster, Image snapshot) {
        this.cluster=cluster;
        if (snapshot != null) throw new IllegalStateException("This lesson requires no snapshots");
        System.out.println("{\"event\":\"EMPTY_START\",\"node\":"+node+",\"generation\":"+generation+"}");
        if (mode.equals("lifecycle")) {
            try { cluster.scheduleTimer(123, 0); throw new IllegalStateException("expected lifecycle rejection"); }
            catch (io.aeron.cluster.client.ClusterException e) {
                if (!e.getMessage().contains("not allowed from onStart")) throw e;
                System.out.println("{\"event\":\"LIFECYCLE_REJECTED\",\"node\":"+node+"}");
            }
            if (state.maySchedule("onStart")) throw new IllegalArgumentException("MISS: lifecycle guard permits onStart");
        }
    }
    public void onSessionMessage(ClientSession session,long timestamp,DirectBuffer buffer,int offset,int length,Header header) {
        long op=length>=8?buffer.getLong(offset):0, id=length>=16?buffer.getLong(offset+8):0;
        long value=length>=24?buffer.getLong(offset+16):0, aux=length>=32?buffer.getLong(offset+24):0;
        long committed=context.commitPositionCounter().get();
        if (cluster.logPosition()>committed) throw new IllegalStateException("callback beyond commit");
        String before=state.canonical();
        boolean accepted=state.apply(op,id,value,aux,length,timestamp,100000L+node*1000L+generation*10000L,3+node+generation,node%2!=0);
        String after=state.canonical();
        System.out.println("{\"event\":\"APPLY\",\"node\":"+node+",\"id\":"+id+",\"op\":"+op+",\"timestamp\":"+timestamp+",\"position\":"+cluster.logPosition()+",\"commit\":"+committed+",\"accepted\":"+accepted+",\"before\":\""+before+"\",\"state\":\""+after+"\"}");
        if (op==9) System.out.println("{\"event\":\"BARRIER\",\"node\":"+node+",\"id\":"+id+",\"count\":"+state.count+",\"total\":"+state.total+",\"state\":\""+after+"\",\"digest\":\""+digest(after)+"\"}");
        if (session!=null) {
            ExpandableArrayBuffer out=new ExpandableArrayBuffer();out.putLong(0,id);
            long result; int tries=0;
            cluster.idleStrategy().reset();
            while ((result=session.offer(out,0,8))<0) {
                if (++tries>1_000_000) throw new IllegalStateException("egress watchdog");
                cluster.idleStrategy().idle();
            }
            if (!state.egressSucceeded(result)) throw new IllegalArgumentException("MISS: positive/mock egress misclassified");
            System.out.println("{\"event\":\"EGRESS\",\"node\":"+node+",\"id\":"+id+",\"role\":\""+cluster.role()+"\",\"result\":"+result+"}");
        }
    }
    public void onSessionOpen(ClientSession s,long t) {}
    public void onSessionClose(ClientSession s,long t,CloseReason r) {}
    public void onTimerEvent(long id,long t) { state.expire(id); }
    public void onTakeSnapshot(ExclusivePublication p) { throw new UnsupportedOperationException("No snapshots in L05"); }
    public void onRoleChange(Cluster.Role role) { System.out.println("{\"event\":\"ROLE\",\"role\":\""+role+"\",\"node\":"+node+"}"); }
    public void onTerminate(Cluster c) {}
}
