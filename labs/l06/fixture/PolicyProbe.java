import java.util.*;
public final class PolicyProbe {
    static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException("MISS: "+reason);}
    public static void main(String[] args) {
        RequestWork s=new RequestWork();
        s.apply(2,41,10,3,0);s.apply(3,41,11,777,0);s.apply(4,41,12,88,9999);
        var first=s.apply(1,41,1,7,0);s.apply(1,41,2,2,0);
        require(s.apply(1,41,1,7,0).equals(first),"return ORIGINAL cached result");
        require(s.count==2&&s.total==27,"one mutation per stable client/request key");
        require(!s.key(41,1).equals(s.key(42,1)),"include client ID in key; different clients may reuse request IDs");
        s.apply(1,42,1,5,0);require(s.count==3&&s.total==42,"include client ID in key");
        String before=s.canonical();
        try{s.apply(1,41,1,8,0);throw new AssertionError("MISS: conflicting duplicate accepted");}catch(IllegalArgumentException expected){}
        require(before.equals(s.canonical()),"conflict changed state");
        try{s.apply(1,41,3,-1,0);throw new AssertionError("MISS: invalid input accepted");}catch(IllegalArgumentException expected){}
        require(before.equals(s.canonical()),"invalid input changed state");
        ClientWork c=new ClientWork();c.submit(1);c.offered(1,128);
        require(c.pending.contains(1L)&&c.retry(1),"offer is not ack; retain and retry pending");
        require(c.reconnectAfterSilence(4_000_000_000L)&&!c.reconnectAfterSilence(1_000_000L),"bounded silent reply reconnect");
        require(c.poll(),"poll egress while waiting");
        require(c.reconnect(-4)&&c.reconnect(-5)&&!c.reconnect(-2),"terminal result recreates publication/session");
        c.reply(1,1,21);c.reply(1,1,21);require(c.pending.isEmpty()&&c.acknowledged.size()==1,"reconcile duplicate wire replies");
        var acknowledgedBefore = Map.copyOf(c.acknowledged);
        var pendingBefore = Set.copyOf(c.pending);
        boolean inconsistentRejected = false;
        try { c.reply(1,9,999); } catch (IllegalArgumentException expected) { inconsistentRejected = true; }
        require(inconsistentRejected,"inconsistent duplicate reply accepted");
        require(c.acknowledged.equals(acknowledgedBefore),"inconsistent reply changed acknowledged results");
        require(c.pending.equals(pendingBefore),"inconsistent reply changed pending requests");
        require(!c.storageAllowed(9,10)&&c.storageAllowed(10,10),"low storage refusal threshold");
        if(args[0].equals("snapshot")) {
            byte[] bytes=s.snapshot();RequestWork restored=new RequestWork();restored.restore(bytes);
            require(restored.canonical().equals(s.canonical()),"snapshot omitted business/dedup/config/timer state");
            require(restored.apply(1,41,1,7,0).equals(first)&&restored.count==3,"snapshot lost original cached reply");
            List<byte[]> invalid=new ArrayList<>();byte[] version=bytes.clone();version[7]=2;invalid.add(version);
            for(int n:new int[]{0,7,20,bytes.length-1})invalid.add(Arrays.copyOf(bytes,n));
            invalid.add(Arrays.copyOf(bytes,bytes.length+1));
            for(byte[] bad:invalid) {
                String prior=restored.canonical();boolean rejected=false;
                try{restored.restore(bad);}catch(IllegalArgumentException e){rejected=true;}
                require(rejected,"reject unsupported/truncated/trailing snapshot");
                require(prior.equals(restored.canonical()),"decode must be atomic on failure");
            }
        }
        System.out.println("{\"event\":\"POLICY_PASS\"}");
    }
}
