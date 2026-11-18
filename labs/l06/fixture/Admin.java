import io.aeron.cluster.*;
import java.io.File;
public final class Admin {
    public static void main(String[] a) {
        File dir=new File(a[0]);
        if(a[1].equals("leader")) {
            ClusterMembership m=new ClusterMembership();
            if(!ClusterTool.listMembers(m,dir,5000))throw new IllegalStateException("membership query timeout");
            System.out.println("{\"event\":\"LEADER\",\"leader\":"+m.leaderMemberId+"}");
        }else if(a[1].equals("snapshot")) {
            boolean accepted=ClusterTool.snapshot(dir,System.out);
            System.out.println("{\"event\":\"SNAPSHOT_REQUEST\",\"accepted\":"+accepted+"}");
        }else if(a[1].equals("recordings")) {
            try(RecordingLog log=new RecordingLog(dir,false)) {
                for(var e:log.entries())if(e.type==RecordingLog.ENTRY_TYPE_SNAPSHOT&&e.isValid)
                    System.out.println("{\"event\":\"SNAPSHOT_ENTRY\",\"service\":"+e.serviceId+",\"position\":"+e.logPosition+",\"recording\":"+e.recordingId+"}");
            }
        }
    }
}
