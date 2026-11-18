import org.agrona.concurrent.UnsafeBuffer;
public final class Primer {
    public static void main(String[] args) {
        byte[] bytes = new byte[4];
        UnsafeBuffer buffer = new UnsafeBuffer(bytes);
        buffer.putByte(0, (byte)7);
        if (buffer.getByte(0) != 7) throw new AssertionError("byte roundtrip");
        System.out.println("PRIMER PASS byte=7");
    }
}
