// Package-private catalog access is ONLY for manufacturing a disposable corrupt fixture.
// Learner record/replay uses public AeronArchive APIs.
package io.aeron.archive;
import io.aeron.archive.client.ArchiveException;
import org.agrona.concurrent.SystemEpochClock;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class TailFixture {
    public static void exercise(Path original,Path copy) throws Exception {
        Files.createDirectory(copy);
        try(var paths=Files.list(original)) { for(Path p:paths.toList())if(Files.isRegularFile(p))Files.copy(p,copy.resolve(p.getFileName())); }
        Path segment;
        try(var paths=Files.list(copy)) {segment=paths.filter(p->p.toString().endsWith(".rec")).findFirst().orElseThrow();}
        long id=Long.parseLong(segment.getFileName().toString().split("-")[0]);
        byte[] old=Files.readAllBytes(segment),data=new byte[old.length];
        for(int offset=0;offset<4032;offset+=64)System.arraycopy(old,0,data,offset,32);
        System.arraycopy(old,0,data,4032,32);
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for(int offset=0;offset<4032;offset+=64) {b.putInt(offset,46);b.putInt(offset+8,offset);}
        b.putInt(4032,96);b.putInt(4032+8,4032);
        Files.write(segment,data);
        try(Catalog c=new Catalog(copy.toFile(),SystemEpochClock.INSTANCE,Catalog.MIN_CAPACITY,true,null,null)) {c.stopPosition(id,-1);}
        try(Catalog c=new Catalog(copy.toFile(),null,0,Catalog.MIN_CAPACITY,SystemEpochClock.INSTANCE,null,null)) {
            throw new AssertionError("ambiguous page-straddling tail unexpectedly recovered");
        }catch(ArchiveException e) {
            if(!e.getMessage().contains("straddling page boundary"))throw e;
            System.out.println("TAIL_STARTUP_REJECTED "+e.getMessage());
        }
        int[] prompts={0};
        boolean valid=ArchiveTool.verify(System.out,copy.toFile(),EnumSet.noneOf(ArchiveTool.VerifyOption.class),null,f->{prompts[0]++;return true;});
        if(!valid||prompts[0]!=1)throw new AssertionError("ArchiveTool tail recovery failed");
        try(Catalog c=new Catalog(copy.toFile(),null,0,Catalog.MIN_CAPACITY,SystemEpochClock.INSTANCE,null,null)) {
            if(c.stopPosition(id)!=4032)throw new AssertionError("tail recovery must truncate at last safe frame start");
        }
        if(!Arrays.equals(Files.readAllBytes(original.resolve(segment.getFileName())),old))throw new AssertionError("original changed");
        System.out.println("{\"event\":\"TAIL_RECOVERED\",\"stop\":4032,\"originalPreserved\":true}");
    }
}
