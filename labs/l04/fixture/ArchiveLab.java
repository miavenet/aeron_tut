import io.aeron.*;
import io.aeron.archive.*;
import io.aeron.archive.client.*;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.archive.checksum.Checksums;
import io.aeron.driver.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.SystemEpochClock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.io.*;

public final class ArchiveLab {
    static final ArchiveWork work = new ArchiveWork();
    static Path root;
    static int base;
    static String dir() { return root.resolve("driver").toString(); }
    static String channel() { return "aeron:udp?endpoint=127.0.0.1:"+(base+4)+"|term-length=64k|mtu=1408"; }
    static String control() { return "aeron:udp?endpoint=127.0.0.1:"+(base+1); }
    static void require(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static void event(String name, String values) { System.out.println("{\"event\":\""+name+"\""+values+"}"); System.out.flush(); }
    static void await(BooleanSupplier test, String name) {
        long deadline = System.nanoTime()+8_000_000_000L;
        while (!test.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("INFRA watchdog: "+name);
            LockSupport.parkNanos(100_000);
        }
    }
    static AeronArchive connect(int stream) { return AeronArchive.connect(work.client(dir(), stream)); }
    static Properties load() throws Exception { Properties p=new Properties(); try (var in=Files.newInputStream(root.resolve("record.properties"))) { p.load(in); } return p; }
    static long val(Properties p,String key) { return Long.parseLong(p.getProperty(key)); }
    static byte[][] tape() {
        int[] sizes={14,73,4097,19,257}; byte[][] out=new byte[sizes.length][];
        for(int i=0;i<out.length;i++) { out[i]=new byte[sizes[i]]; for(int j=0;j<sizes[i];j++) out[i][j]=(byte)(i*31+j*7); out[i][0]=(byte)i; }
        return out;
    }
    static void send(Publication pub,byte[] bytes) {
        UnsafeBuffer b=new UnsafeBuffer(bytes);
        await(()-> { long r=pub.offer(b); if(r==Publication.CLOSED || r==Publication.MAX_POSITION_EXCEEDED) throw new AssertionError("terminal offer="+r); return r>=0; },"offer");
    }
    static long discover(AeronArchive archive, Publication pub, long subscription) {
        var counters=archive.context().aeron().countersReader(); int[] c={-1};
        await(()->(c[0]=RecordingPos.findCounterIdBySession(counters,pub.sessionId(),archive.archiveId()))>=0,"RecordingPos identity");
        long id=work.recordingId(counters,c[0],subscription);
        require(id==RecordingPos.getRecordingId(counters,c[0]),"Use RecordingPos recording identity, not startRecording subscription ID");
        return id;
    }
    static void server(String option) throws Exception {
        boolean empty=option.equals("empty");
        Archive.Context ac=new Archive.Context().aeronDirectoryName(dir()).archiveDir(root.resolve("archive").toFile())
            .threadingMode(ArchiveThreadingMode.SHARED).segmentFileLength(65536).connectTimeoutNs(700_000_000L)
            .errorHandler(t->System.out.println("ARCHIVE_ERROR "+t));
        work.configure(ac,control());
        if(option.equals("checksum")) ac.recordChecksum(Checksums.crc32()).replayChecksum(Checksums.crc32());
        try (ArchivingMediaDriver driver=ArchivingMediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(dir()).dirDeleteOnStart(true).dirDeleteOnShutdown(false)
            .threadingMode(ThreadingMode.SHARED).spiesSimulateConnection(empty?false:work.simulateSpies())
            .publicationTermBufferLength(65536).ipcTermBufferLength(65536).socketRcvbufLength(0).socketSndbufLength(0),ac)) {
            event("READY",",\"fileSync\":"+ac.fileSyncLevel()+",\"catalogSync\":"+ac.catalogFileSyncLevel());
            while(!Files.exists(root.resolve("stop"))) LockSupport.parkNanos(1_000_000);
        }
    }
    static void record(String option) throws Exception {
        try(AeronArchive archive=connect(101)) {
            long subscription=archive.startRecording(channel(),404,SourceLocation.LOCAL);
            String ch=channel();
            if(option.equals("nonzero")) ch=new ChannelUriStringBuilder(ch).initialPosition(4096,17,65536).build();
            try(ExclusivePublication pub=archive.context().aeron().addExclusivePublication(ch,404)) {
                long id=discover(archive,pub,subscription);
                long start=archive.getStartPosition(id);
                require(archive.getStopPosition(id)==AeronArchive.NULL_POSITION,"Live catalog stop must be NULL_POSITION");
                if(option.equals("empty")) {
                    long r=pub.offer(new UnsafeBuffer(tape()[0]));
                    require(r==Publication.NOT_CONNECTED,"No-subscriber spy scenario must reject offer");
                    require(pub.position()==start,"Rejected offer must not advance");
                    archive.stopRecording(subscription);
                    await(()->archive.getStopPosition(id)!=AeronArchive.NULL_POSITION,"empty catalog stop");
                    require(archive.getStopPosition(id)==start,"empty recording start equals stop");
                    try {archive.startReplay(id,start,1,"aeron:ipc",407);throw new AssertionError("empty recording replay accepted");}
                    catch(ArchiveException e) {require(e.errorCode()==ArchiveException.INVALID_POSITION,"empty range rejected");}
                    event("EMPTY",",\"offer\":"+r+",\"start\":"+start+",\"stop\":"+archive.getStopPosition(id));
                    return;
                }
                require(work.simulateSpies(),"Enable spy simulation for this no-network-subscriber fixture");
                await(pub::isConnected,"spy simulated connection");
                for(byte[] b:tape()) send(pub,b);
                long target=pub.position();
                var counters=archive.context().aeron().countersReader();
                int counter=RecordingPos.findCounterIdByRecording(counters,id,archive.archiveId());
                require(!work.recordingComplete(target-32,target,true) && !work.recordingComplete(target,target,false)
                    && work.recordingComplete(target,target,true) && work.recordingComplete(target+32,target,true),"Wait for an active recording counter to cover the offered position");
                require(!work.stopped(AeronArchive.NULL_POSITION) && work.stopped(start) && work.stopped(target),"Recognize NULL_POSITION as live, including nonzero starts");
                await(()->work.recordingComplete(counters.getCounterValue(counter),target,RecordingPos.isActive(counters,counter,id)),"RecordingPos reaches offered target");
                require(counters.getCounterValue(counter)>=target,"Actual recording progress oracle");
                archive.stopRecording(subscription);
                await(()->work.stopped(archive.getStopPosition(id)),"catalog stopped");
                long stop=archive.getStopPosition(id);
                require(stop==target,"catalog stop equals final publication position");
                Properties p=new Properties();
                p.setProperty("id",""+id);p.setProperty("subscription",""+subscription);p.setProperty("start",""+start);p.setProperty("stop",""+stop);
                p.setProperty("session",""+pub.sessionId());p.setProperty("initialTerm",""+pub.initialTermId());
                require(archive.listRecording(id,(cs,co,rid,st,et,sp,ep,it,seg,term,mtu,sess,stream,stripped,original,source)-> {
                    p.setProperty("term",""+term);p.setProperty("mtu",""+mtu);p.setProperty("segmentLength",""+seg);
                })==1,"descriptor discovered");
                try(var out=Files.newOutputStream(root.resolve("record.properties"))) { p.store(out,"actual recording descriptor"); }
                try(var paths=Files.list(root.resolve("archive"))) {
                    String segment=paths.filter(f->f.getFileName().toString().startsWith(id+"-")&&f.toString().endsWith(".rec")).findFirst().orElseThrow().getFileName().toString();
                    event("RECORDED",",\"id\":"+id+",\"subscription\":"+subscription+",\"start\":"+start+",\"stop\":"+stop+",\"segment\":\""+segment+"\"");
                }
            }
        }
    }
    static void replay() throws Exception {
        Properties p=load();long id=val(p,"id"),start=val(p,"start"),stop=val(p,"stop");
        try(AeronArchive archive=connect(102)) {
            long chosen=work.replayStart(start,stop);
            require(chosen==start,"Discover catalog start; do not assume zero or skip first frame");
            // A completed replay image can leave the subscription before our
            // next poll. Retain its identity: Image.position() preserves the
            // final consumed position after closure in the pinned release.
            var replayImage=new java.util.concurrent.atomic.AtomicReference<Image>();
            try(Subscription sub=archive.replay(id,chosen,stop-chosen,"aeron:ipc",405,replayImage::set,image->{})) {
                List<byte[]> got=new ArrayList<>();
                var handler=work.receiver((b,o,n,h)-> { byte[] bytes=new byte[n];b.getBytes(o,bytes);got.add(bytes); });
                await(()-> { sub.poll(handler,20);Image image=replayImage.get();return got.size()>=tape().length || (image!=null && image.isEndOfStream()); },"replay exact messages");
                require(got.size()==tape().length,"Reassemble complete messages; exact count");
                for(int i=0;i<tape().length;i++) require(Arrays.equals(got.get(i),tape()[i]),"Replay bytes/order mismatch at ID "+i);
                await(()-> { sub.poll(handler,20);Image image=replayImage.get();return image!=null && image.position()==stop; },"replay terminal position");
                require(got.size()==tape().length,"No additional replay messages");
                sub.close();
                require(replayImage.get().position()==stop,"Retained image keeps final position after subscription close");
                event("REPLAYED",",\"count\":"+got.size()+",\"start\":"+start+",\"stop\":"+stop);
            }
            for(long bad:(start>0 ? new long[]{start-32,start+1,start+32}:new long[]{start+1,start+32})) {
                try { archive.startReplay(id,bad,stop-bad,"aeron:ipc",406);throw new AssertionError("invalid replay offset accepted "+bad); }
                catch(ArchiveException e) { require(bad==start+1 ? e.getMessage().contains("FRAME_ALIGNMENT") : e.errorCode()==ArchiveException.INVALID_POSITION,"Expected offset rejection: "+e);event("INVALID_OFFSET",",\"position\":"+bad+",\"code\":"+e.errorCode()); }
            }
        }
    }
    static void config() {
        for(int test=0;test<5;test++) {
            try {
                if(test<3) {
                    Archive.Context c=new Archive.Context().controlChannel(control()).replicationChannel("aeron:udp?endpoint=127.0.0.1:0");
                    if(test==0)c.controlChannel(null); if(test==1)c.replicationChannel(null);if(test==2)c.fileSyncLevel(1).catalogFileSyncLevel(0);
                    c.conclude();
                } else {
                    { AeronArchive.Context c=new AeronArchive.Context();
                        if(test==3)c.controlRequestChannel(null).controlResponseChannel("aeron:ipc");
                        else c.controlRequestChannel("aeron:ipc").controlResponseChannel(null);
                        c.conclude();
                    }
                }
                throw new AssertionError("invalid context accepted "+test);
            } catch(io.aeron.exceptions.ConfigurationException e) { event("CONFIG_REJECTED",",\"case\":"+test); }
        }
        Archive.Context candidate=new Archive.Context();work.configure(candidate,control());
        require(candidate.controlChannel()!=null && candidate.replicationChannel()!=null,"Set control and replication channels");
        require(candidate.fileSyncLevel()<=candidate.catalogFileSyncLevel(),"catalog sync cannot be below file sync");
        require(!candidate.deleteArchiveOnStart(),"Preserve persistent archive directory");
        { var c=work.client(dir(),111);require(c.controlRequestChannel()!=null&&c.controlResponseChannel()!=null,"Set request and response channels");}
    }
    static void merge() throws Exception {
        try(AeronArchive a=connect(110);Subscription s=a.context().aeron().addSubscription("aeron:ipc",600)) {
            try(ReplayMerge merge=new ReplayMerge(s,a,"aeron:ipc","aeron:udp?endpoint=127.0.0.1:0",channel(),0,0,SystemEpochClock.INSTANCE,1000)) {
                throw new AssertionError("IPC ReplayMerge accepted");
            } catch(IllegalArgumentException e) { require(e.getMessage().contains("IPC merging"),"IPC constructor rejection"); }
            work.requirePrivateMergeClient(false);
            try {work.requirePrivateMergeClient(true);throw new AssertionError("application must reject sharing merge client");}catch(IllegalArgumentException expected) {}
            event("MERGE_GUARDS",",\"ipcRejected\":true,\"sharedGuard\":true");
        }
    }
    static void isolation() throws Exception {
        try(AeronArchive a=connect(120);AeronArchive b=connect(121)) {
            require(a.context().controlResponseStreamId()!=b.context().controlResponseStreamId(),"Give clients distinct response streams");
            long wildcard=a.startRecording(channel(),410,SourceLocation.LOCAL);
            try(ExclusivePublication p=a.context().aeron().addExclusivePublication(channel(),410);
                ExclusivePublication q=b.context().aeron().addExclusivePublication(channel(),410)) {
                long x=discover(a,p,wildcard),y=discover(b,q,wildcard);
                require(x!=y,"wildcard image identities distinct");
                long specific=b.startRecording(ChannelUri.addSessionId(channel(),p.sessionId()),410,SourceLocation.LOCAL);
                List<Long> ids=new ArrayList<>();
                await(()-> { ids.clear();a.listRecordings(0,100,(cs,co,rid,st,et,sp,ep,it,seg,term,mtu,sess,stream,s,o,src)->{if(stream==410)ids.add(rid);});return ids.size()==3; },"wildcard plus specific descriptors");
                await(()->p.isConnected()&&q.isConnected(),"two publication images");send(p,tape()[0]);send(q,tape()[1]);
                await(()->ids.stream().allMatch(id->a.getRecordingPosition(id)>a.getStartPosition(id)),"three recordings progress");
                b.stopRecording(specific);a.stopRecording(wildcard);
                event("ISOLATION",",\"images\":2,\"recordings\":3,\"clients\":2");
            }
        }
    }
    static void lifecycle() throws Exception {
        require(!work.deadlineExpired(9,10)&&work.deadlineExpired(10,10)&&work.deadlineExpired(11,10),"Deadline policy must bound replay waiting");
        Properties p=load();long id=val(p,"id"),start=val(p,"start"),stop=val(p,"stop");
        try(AeronArchive a=connect(130)) {
            a.startReplay(id,start,stop-start,"aeron:udp?endpoint=127.0.0.1:"+(base+5),420);
            String[] error={null};await(()->(error[0]=a.pollForErrorResponse())!=null,"never-connected replay error");
            require(error[0].contains("no connection established"),"Expected never-connect deadline error: "+error[0]);
            // A live follow replay stays open after this prefix; close its subscriber at a semantic barrier.
            long subId=a.startRecording("aeron:ipc",421,SourceLocation.LOCAL);
            try(ExclusivePublication pub=a.context().aeron().addExclusivePublication("aeron:ipc",421)) {
                long live=discover(a,pub,subId);await(pub::isConnected,"IPC recorder");send(pub,tape()[0]);
                await(()->a.getRecordingPosition(live)>0,"live prefix recorded");
                try(Subscription sub=a.replay(live,0,Long.MAX_VALUE,"aeron:ipc",422)) {
                    int[] count={0};await(()->{sub.poll((b,o,n,h)->count[0]++,10);return count[0]==1;},"follow replay prefix");
                }
                // Query the release's active-replay counter after subscriber disconnect.
                await(()->activeReplays(a)==0,"replay removed after disconnect");
                a.stopRecording(subId);
            }
            event("LIFECYCLE",",\"neverConnectError\":true,\"disconnectRemovedReplay\":true");
        }
    }
    static long activeReplays(AeronArchive a) {
        long[] value={-1};var c=a.context().aeron().countersReader();
        c.forEach((id,type,key,label)-> {if(type==AeronCounters.ARCHIVE_REPLAY_SESSION_COUNT_TYPE_ID)value[0]=c.getCounterValue(id);});return value[0];
    }
    static void geometry() throws Exception {
        Properties p=load();long id=val(p,"id"),stop=val(p,"stop");
        try(AeronArchive a=connect(140)) {
            String wrong=new ChannelUriStringBuilder("aeron:ipc").mtu(1024).initialPosition(stop,(int)val(p,"initialTerm"),(int)val(p,"term")).build();
            long subscription=a.extendRecording(id,wrong,404,SourceLocation.LOCAL);
            try(ExclusivePublication pub=a.context().aeron().addExclusivePublication(wrong,404)) {
                String[] error={null};await(()->(error[0]=a.pollForErrorResponse())!=null,"extension mismatch error");
                require(error[0].contains("mtuLength"),"Expected MTU mismatch: "+error[0]);
            }
            a.tryStopRecording(subscription);
            String right=work.extensionChannel(stop,(int)val(p,"initialTerm"),(int)val(p,"term"),(int)val(p,"mtu"));
            ChannelUri expected=ChannelUri.parse(new ChannelUriStringBuilder("aeron:ipc").mtu((int)val(p,"mtu")).initialPosition(stop,(int)val(p,"initialTerm"),(int)val(p,"term")).build());
            require(ChannelUri.parse(right).equals(expected),"Preserve descriptor MTU/term/initial position on extension");
            long good=a.extendRecording(id,right,404,SourceLocation.LOCAL);
            try(ExclusivePublication pub=a.context().aeron().addExclusivePublication(right,404)) {
                discover(a,pub,good);await(pub::isConnected,"extension connected");send(pub,tape()[0]);long target=pub.position();
                await(()->a.getRecordingPosition(id)==target,"extension recorded");a.stopRecording(good);
                await(()->a.getStopPosition(id)==target,"extended catalog stop");
                require(target>stop,"extension advances same recording");
            }
            event("GEOMETRY",",\"badRejected\":true,\"matchingExtended\":true");
        }
    }
    static void storage() throws Exception {
        Path archive=root.resolve("archive");Properties p=load();long id=val(p,"id");
        Path segment;
        try(var paths=Files.list(archive)) {segment=paths.filter(f->f.toString().endsWith(".rec")).findFirst().orElseThrow();}
        byte[] original=Files.readAllBytes(segment);
        java.nio.ByteBuffer frame=java.nio.ByteBuffer.wrap(original).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int firstAligned=(frame.getInt(0)+31)&-32;
        UnsafeBuffer checksumBytes=new UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(firstAligned));
        checksumBytes.putBytes(0,original,0,firstAligned);
        int checksum=Checksums.crc32().compute(checksumBytes.addressOffset(),32,firstAligned-32);
        require(frame.getInt(12)==checksum,"On-disk session slot must contain computed frame checksum");
        require(ArchiveTool.verify(System.out,archive.toFile(),EnumSet.of(ArchiveTool.VerifyOption.APPLY_CHECKSUM),work.verificationChecksum(),f->false),"intact checksum verification");
        // Mutate only a disposable copy, including its catalog: verify can mark entries INVALID.
        Path bad=root.resolve("bad-checksum");Files.createDirectory(bad);
        try(var paths=Files.list(archive)) {for(Path f:paths.toList()) if(Files.isRegularFile(f))Files.copy(f,bad.resolve(f.getFileName()));}
        byte[] corrupt=original.clone();corrupt[32]^=0x55;Files.write(bad.resolve(segment.getFileName()),corrupt);
        boolean accepted=ArchiveTool.verify(System.out,bad.toFile(),EnumSet.of(ArchiveTool.VerifyOption.APPLY_CHECKSUM),work.verificationChecksum(),f->false);
        require(!accepted,"Corrupted payload must fail checksum verification; do not omit checksum");
        require(Arrays.equals(Files.readAllBytes(segment),original),"original recording unchanged");
        event("CHECKSUM",",\"intactPass\":true,\"corruptRejected\":true,\"originalPreserved\":true");
    }
    static void tool() throws Exception {
        require(!work.allowTruncate(false)&&work.allowTruncate(true),"Permit destructive tail recovery only on the disposable copy");
        require(work.descriptorCommand().equals("describe"),"Use tested describe dispatch, not help-only capacity");
        io.aeron.archive.TailFixture.exercise(root.resolve("archive"),root.resolve("tail-copy"));
    }
    public static void main(String[] args) throws Exception {
        root=Path.of(args[1]);base=Integer.parseInt(args[2]);String option=args.length>3?args[3]:"normal";
        try {
            switch(args[0]) {
                case "server":server(option);break;case "record":record(option);break;case "replay":replay();break;
                case "config":config();break;case "merge":merge();break;case "isolation":isolation();break;
                case "lifecycle":lifecycle();break;case "geometry":geometry();break;
                case "storage":storage();break;case "tool":tool();break;
                case "checksum-probe":require(Checksums.crc32()!=null,"CRC implementation available");break;
                default:throw new IllegalArgumentException(args[0]);
            }
            event("PASS",",\"mode\":\""+args[0]+"\"");
        } catch(AssertionError e) { System.out.println("MISS: "+e.getMessage());System.exit(1); }
    }
}
