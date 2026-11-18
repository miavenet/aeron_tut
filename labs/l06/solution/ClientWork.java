import java.util.*;
public final class ClientWork {
    // Pending is application-owned; reconnecting an Aeron session must not clear it.
    public final Set<Long> pending=new LinkedHashSet<>();
    public final Map<Long,String> acknowledged=new TreeMap<>();
    public void submit(long id) { if(!acknowledged.containsKey(id)) pending.add(id); }
    public void offered(long id,long result) { /* local acceptance does not acknowledge */ }
    public boolean retry(long id) { return pending.contains(id); }
    public void reply(long id,long count,long total) {
        String result=count+","+total;
        String previous=acknowledged.putIfAbsent(id,result);
        if(previous!=null && !previous.equals(result))throw new IllegalArgumentException("inconsistent cached reply");
        pending.remove(id);
    }
    public boolean reconnectAfterSilence(long idleNs) { return idleNs>3_000_000_000L; }
    public boolean poll() { return true; }
    public boolean reconnect(long offerResult) { return offerResult==-4 || offerResult==-5; }
    public boolean storageAllowed(long usable,long reserve) { return usable>=reserve; }
}
