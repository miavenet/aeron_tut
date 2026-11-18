import org.agrona.concurrent.AgentInvoker;

public final class Diagnostics {
    // Compare a single channel/stream/session; missing counters are not zero.
    public String diagnose(long hwm, long rebuilt, long consumed, long ageMs, long staleAfterMs) {
        if (ageMs > staleAfterMs) return "STALE_DRIVER";
        if (hwm < 0 || rebuilt < 0 || consumed < 0) return "UNKNOWN";
        if (hwm > rebuilt) return "GAP";
        if (rebuilt > consumed) return "SLOW_CONSUMER";
        return "DRAINED";
    }
    public long lag(long rebuilt, long consumed) { return rebuilt - consumed; }
    public boolean resumePolling() { return true; }
    public void duty(AgentInvoker invoker) { invoker.invoke(); }
    public boolean needsReplay(long expectedNext, long firstReceived) { return false; }
    // Application trace model: windows already include each receiver's consumption position.
    public long limit(String policy, long[] windows, boolean[] tagged) {
        long v = policy.equals("max") ? Long.MIN_VALUE : Long.MAX_VALUE;
        for (int i=0; i<windows.length; i++) {
            if (policy.equals("tagged") && !tagged[i]) continue;
            v = policy.equals("max") ? Math.max(v, windows[i]) : Math.min(v, windows[i]);
        }
        return v;
    }
}
