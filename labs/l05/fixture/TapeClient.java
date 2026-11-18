import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.*;
import org.agrona.ExpandableArrayBuffer;
import java.util.*;

public final class TapeClient {
    public static void main(String[] args) throws Exception {
        ClusterNode.PORT_BASE=Integer.parseInt(args[1]);
        String endpoints="0=localhost:"+ClusterNode.port(0,2)+",1=localhost:"+ClusterNode.port(1,2)+",2=localhost:"+ClusterNode.port(2,2);
        Set<Long> replies=new HashSet<>();
        try (MediaDriver d=MediaDriver.launchEmbedded(new MediaDriver.Context().aeronDirectoryName(args[0])
                .threadingMode(ThreadingMode.SHARED).mtuLength(512).dirDeleteOnStart(true).dirDeleteOnShutdown(true));
            AeronCluster c=AeronCluster.connect(new AeronCluster.Context().aeronDirectoryName(d.aeronDirectoryName())
                .ingressChannel("aeron:udp").ingressEndpoints(endpoints).egressChannel("aeron:udp?endpoint=localhost:0")
                .messageTimeoutNs(20_000_000_000L)
                .egressListener((s,t,b,o,l,h)->replies.add(b.getLong(o))))) {
            System.out.println("{\"event\":\"CONNECTED\",\"leader\":"+c.leaderMemberId()+"}");
            long[][] tape={{2,10,2,0},{3,11,700,0},{1,12,5,0},{1,13,-7,0},{4,14,9,2},{2,15,3,0},{1,16,4,0},{9,900,0,0}};
            if (args[2].equals("suffix")) tape=new long[][]{{1,20,8,0},{9,901,0,0}};
            ExpandableArrayBuffer b=new ExpandableArrayBuffer();
            for(long[] row:tape) {
                for(int i=0;i<4;i++) b.putLong(i*8,row[i]);
                long end=System.nanoTime()+20_000_000_000L;
                while(c.offer(b,0,32)<0) { if(System.nanoTime()>end)throw new IllegalStateException("offer watchdog");c.pollEgress();Thread.sleep(1); }
                while(!replies.contains(row[1])) { if(System.nanoTime()>end)throw new IllegalStateException("reply watchdog");c.pollEgress();c.sendKeepAlive();Thread.sleep(1); }
            }
            System.out.println("{\"event\":\"CLIENT_DONE\",\"replies\":"+replies.size()+"}");
        }
    }
}
