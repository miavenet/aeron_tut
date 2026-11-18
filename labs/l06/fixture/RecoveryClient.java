import io.aeron.cluster.client.*;
import io.aeron.driver.*;
import org.agrona.ExpandableArrayBuffer;
import java.nio.file.*;
import java.util.*;
public final class RecoveryClient {
    public static void main(String[] args)throws Exception {
        ClusterNode.PORT_BASE=Integer.parseInt(args[1]);String mode=args[3];Path gate=Path.of(args[4]);
        List<long[]> tape=new ArrayList<>();for(String line:Files.readAllLines(Path.of(args[2])))
            if(!line.isBlank())tape.add(Arrays.stream(line.split(",")).mapToLong(Long::parseLong).toArray());
        ClientWork work=new ClientWork();for(long[] row:tape)work.submit(row[2]);
        String endpoints="0=localhost:"+ClusterNode.port(0,2)+",1=localhost:"+ClusterNode.port(1,2)+",2=localhost:"+ClusterNode.port(2,2);
        if(mode.equals("wrong-endpoint")) endpoints="0=localhost:1";
        final boolean[] dropped={false};final long[] wireReplies={0},lastReply={System.nanoTime()};
        long end=System.nanoTime()+90_000_000_000L;AeronCluster c=null;
        try(MediaDriver d=MediaDriver.launchEmbedded(new MediaDriver.Context().aeronDirectoryName(args[0])
            .threadingMode(ThreadingMode.SHARED).mtuLength(512).dirDeleteOnStart(true).dirDeleteOnShutdown(true))) {
            try {
                int index=0;long sentAt=0,keepaliveAt=0;
                while(index<tape.size()) {
                    if(System.nanoTime()>end)throw new IllegalStateException("client watchdog pending="+work.pending);
                    if(c==null || c.isClosed()) {
                        try {
                            c=AeronCluster.connect(new AeronCluster.Context().aeronDirectoryName(d.aeronDirectoryName())
                                .ingressChannel("aeron:udp").ingressEndpoints(endpoints).egressChannel("aeron:udp?endpoint=localhost:0")
                                .messageTimeoutNs(5_000_000_000L)
                                .egressListener((s,t,b,o,l,h)->{
                                    wireReplies[0]++;lastReply[0]=System.nanoTime();long id=b.getLong(o);
                                    if(mode.equals("lost-kill")&&id==2&&!dropped[0]) {
                                        dropped[0]=true;System.out.println("{\"event\":\"DROPPED\",\"id\":2,\"count\":"+b.getLong(o+8)+",\"total\":"+b.getLong(o+16)+"}");return;
                                    }
                                    work.reply(id,b.getLong(o+8),b.getLong(o+16));
                                    System.out.println("{\"event\":\"ACK\",\"id\":"+id+",\"count\":"+b.getLong(o+8)+",\"total\":"+b.getLong(o+16)+"}");
                                }));
                            lastReply[0]=System.nanoTime();
                            System.out.println("{\"event\":\"CONNECTED\",\"leader\":"+c.leaderMemberId()+",\"session\":"+c.clusterSessionId()+"}");
                        } catch(io.aeron.exceptions.AeronException e) {
                            String reason=e.getMessage().contains("concurrent session limit")?"session-limit":(e instanceof io.aeron.exceptions.TimeoutException?"timeout":"other");
                            System.err.println(e.toString());
                            System.out.println("{\"event\":\"CONNECT_RETRY\",\"reason\":\""+reason+"\"}");Thread.sleep(10);continue;
                        }
                    }
                    if(mode.equals("idle-session") || mode.equals("hold-session")) {
                        System.out.println("{\"event\":\"IDLE\"}");
                        while(!Files.exists(gate)) {
                            if(System.nanoTime()>end)throw new IllegalStateException("idle watchdog");
                            if(mode.equals("hold-session")) {
                                c.pollEgress();
                                if(System.nanoTime()-keepaliveAt>200_000_000L) {
                                    c.sendKeepAlive();keepaliveAt=System.nanoTime();
                                }
                            }
                            Thread.sleep(10);
                        }
                        break;
                    }
                    try {
                        if(work.poll())c.pollEgress();
                        if(System.nanoTime()-keepaliveAt>200_000_000L){c.sendKeepAlive();keepaliveAt=System.nanoTime();}
                    } catch(io.aeron.exceptions.AeronException e){c.close();c=null;continue;}
                    if(mode.equals("gated")&&!Files.exists(gate) || mode.equals("lost-kill")&&dropped[0]&&!Files.exists(gate)) {Thread.sleep(1);continue;}
                    if(work.reconnectAfterSilence(System.nanoTime()-lastReply[0])){c.close();c=null;continue;}
                    long[] row=tape.get(index);long id=row[2];
                    if(work.acknowledged.containsKey(id)){index++;sentAt=0;continue;}
                    if(work.retry(id)&&System.nanoTime()-sentAt>=200_000_000L) {
                        ExpandableArrayBuffer b=new ExpandableArrayBuffer();for(int i=0;i<5;i++)b.putLong(8*i,row[i]);
                        long rc=c.offer(b,0,40);work.offered(id,rc);sentAt=System.nanoTime();
                        System.out.println("{\"event\":\"OFFER\",\"id\":"+id+",\"result\":"+rc+"}");
                        if(work.reconnect(rc)){c.close();c=null;}
                    }
                    Thread.sleep(1);
                }
                System.out.println("{\"event\":\"DONE\",\"unique\":"+work.acknowledged.size()+",\"pending\":"+work.pending.size()+",\"wireReplies\":"+wireReplies[0]+"}");
            }finally{if(c!=null)c.close();}
        }
    }
}
