import java.io.*;
import java.util.*;

/** Application protocol, deliberately small enough to inspect in a sitting. */
public final class RequestWork {
    public long count, total, config=1, tick, timerId=-1, timerDeadline;
    public final TreeMap<String,Result> results=new TreeMap<>();
    public record Result(long op,long value,long aux,long count,long total) {}
    public String key(long client,long request) { return client+":"+request; }
    public Result apply(long op,long client,long id,long value,long aux) {
        if(client<=0 || id<=0 || !(op==9 || op==1&&value>=0&&value<=1000 ||
            op==2&&value>=1&&value<=10 || op==3&&value>=0 || op==4&&value>0&&aux>=0))
            throw new IllegalArgumentException("invalid input");
        if(op==9) return new Result(op,value,aux,count,total);
        String key=key(client,id);
        Result cached=results.get(key);
        if(cached!=null) {
            if(cached.op()!=op || cached.value()!=value || cached.aux()!=aux)
                throw new IllegalArgumentException("request key reused with different payload");
            return cached;
        }
        if(op==1) { count++; total+=value*config; }
        if(op==2) config=value;
        if(op==3) tick=value;
        if(op==4) { timerId=value; timerDeadline=aux; }
        Result r=new Result(op,value,aux,count,total);
        results.put(key,r);
        return r;
    }
    public String canonical() {
        return count+","+total+","+config+","+tick+","+timerId+","+timerDeadline+"|"+results;
    }
    public byte[] snapshot() {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(0x57503553);out.writeInt(1);
            out.writeLong(count);out.writeLong(total);out.writeLong(config);
            out.writeLong(tick);out.writeLong(timerId);out.writeLong(timerDeadline);
            out.writeInt(results.size());
            for(var e:results.entrySet()) {
                out.writeUTF(e.getKey());Result r=e.getValue();
                out.writeLong(r.op());out.writeLong(r.value());out.writeLong(r.aux());
                out.writeLong(r.count());out.writeLong(r.total());
            }
            out.flush();return bytes.toByteArray();
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public void restore(byte[] bytes) {
        // Decode into a temporary object; malformed data must not half-restore live state.
        RequestWork next=new RequestWork();
        try {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=0x57503553 || in.readInt()!=1) throw new IOException("unsupported snapshot version/magic");
            next.count=in.readLong();next.total=in.readLong();next.config=in.readLong();
            next.tick=in.readLong();next.timerId=in.readLong();next.timerDeadline=in.readLong();
            int size=in.readInt();if(size<0 || size>10000)throw new IOException("bad dedup size");
            for(int i=0;i<size;i++) {
                String key=in.readUTF();Result r=new Result(in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readLong());
                if(next.results.put(key,r)!=null)throw new IOException("duplicate snapshot key");
            }
            if(in.available()!=0 || next.config<1 || next.config>10 || next.count<0)
                throw new IOException("bad snapshot fields/trailing bytes");
        } catch(IOException e) { throw new IllegalArgumentException("invalid snapshot",e); }
        count=next.count;total=next.total;config=next.config;tick=next.tick;
        timerId=next.timerId;timerDeadline=next.timerDeadline;
        results.clear();results.putAll(next.results);
    }
}
