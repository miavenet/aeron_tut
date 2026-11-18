import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class StateWork {
    public long count, total, config = 1, tick, lastTime, order = 17;
    public long timerId = -1, expiries;
    public boolean timerActive;

    // Wire commands: 1=add, 2=config, 3=tick, 4=ordered batch, 9=barrier.
    public boolean valid(long op, long id, long value, long aux, int length) {
        return length == 32 && id > 0 &&
            ((op == 1 && value >= 0 && value <= 1000) ||
             (op == 2 && value >= 1 && value <= 10) ||
             (op == 3 && value >= 0) ||
             (op == 4 && value >= 0 && value <= 1000 && aux >= 0 && aux <= 1000) || op == 9);
    }
    public long eventTime(long logged, long local) { return logged; }
    public long multiplier(long loggedConfig, long localConfig) { return loggedConfig; }
    public long[] traversal(long a, long b, boolean reverse) {
        long[] values = {a, b}; Arrays.sort(values); return values;
    }
    public boolean apply(long op, long id, long value, long aux, int length,
                         long timestamp, long localClock, long localConfig, boolean reverse) {
        if (!valid(op, id, value, aux, length)) return false;
        if (op == 9) return true;
        lastTime = eventTime(timestamp, localClock);
        if (op == 2) config = value;
        if (op == 3) tick = value;
        if (op == 1) { count++; total += value * multiplier(config, localConfig); }
        if (op == 4) {
            for (long v : traversal(value, aux, reverse)) { order = order * 31 + v; }
        }
        return true;
    }
    public boolean egressSucceeded(long result) { return result > 0; }
    public long commitLimit(long committed, long appended) { return committed; }
    public long millis(long value, TimeUnit unit) { return unit.toMillis(value); }
    public boolean maySchedule(String callback) {
        return callback.equals("onSessionMessage") || callback.equals("onTimerEvent") ||
            callback.equals("onSessionOpen") || callback.equals("onSessionClose");
    }
    // Application cancellation takes effect in log order, even if a timer event arrives later.
    public void schedule(long id) { timerId = id; timerActive = true; }
    public void cancel(long id) { timerActive = false; }
    public void expire(long id) {
        if (timerActive && id == timerId) { expiries++; timerActive = false; }
    }
    public String canonical() {
        return count+","+total+","+config+","+tick+","+lastTime+","+order;
    }
}
