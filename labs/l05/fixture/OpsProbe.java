import java.util.concurrent.TimeUnit;
public final class OpsProbe {
    static void require(boolean ok,String why){if(!ok)throw new IllegalArgumentException("MISS: "+why);}
    public static void main(String[] args) {
        StateWork w=new StateWork();String mode=args[0];
        if(mode.equals("policy")) {
            for(long n:new long[]{1,64,128})require(w.egressSucceeded(n),"positive/mock egress success "+n);
            for(long n:new long[]{-1,-2,-3,-4,-5,0})require(!w.egressSucceeded(n),"nonpositive egress "+n);
            for(String cb:new String[]{"onStart","onRoleChange","onTerminate","doBackgroundWork","onTakeSnapshot"})require(!w.maySchedule(cb),"lifecycle policy "+cb);
            for(String cb:new String[]{"onSessionMessage","onTimerEvent","onSessionOpen","onSessionClose"})require(w.maySchedule(cb),"allowed callback "+cb);
        } else if(mode.equals("clock")) {
            require(w.millis(2_000_000,TimeUnit.NANOSECONDS)==2,"nanoseconds converted explicitly");
            require(w.millis(2000,TimeUnit.MICROSECONDS)==2,"microseconds converted explicitly");
            require(w.eventTime(90,1000)==90 && w.eventTime(80,2000)==80,"logged clock may go backwards");
        } else if(mode.equals("timers")) {
            w.schedule(7);w.cancel(7);w.expire(7);require(w.expiries==0,"cancel-before-expiry must suppress business effect");
            w.schedule(8);w.expire(8);w.cancel(8);w.expire(8);require(w.expiries==1,"expiry-before-cancel applies once");
            w.schedule(9);w.cancel(8);w.expire(8);require(w.timerActive,"stale ID must not cancel new timer");
            w.expire(9);require(w.expiries==2,"current timer still executes");
            // Explicit permutations of two distinct timers: no promised library tie order.
            for(long[] order:new long[][]{{21,22},{22,21}}) {
                StateWork a=new StateWork(),b=new StateWork();a.schedule(21);b.schedule(22);
                for(long id:order){a.expire(id);b.expire(id);}
                require(a.expiries==1&&b.expiries==1,"tie permutation must preserve independent effects");
            }
        } else if(mode.equals("validation")) {
            long[][] bad={{1,1,-1,0},{2,2,0,0},{4,3,1,-1},{42,4,0,0},{1,0,2,0}};
            for(long[] row:bad){String before=w.canonical();require(!w.apply(row[0],row[1],row[2],row[3],32,5,6,7,true),"invalid command accepted");require(w.canonical().equals(before),"invalid command mutated state");}
            require(!w.apply(1,1,2,0,24,5,6,7,false),"truncated command");
            require(w.canonical().equals("0,0,1,0,0,17"),"truncation mutated state");
        } else throw new IllegalArgumentException("unknown mode");
        System.out.println("{\"event\":\"OPS_PASS\",\"case\":\""+mode+"\"}");
    }
}
