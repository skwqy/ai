import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

/** Case E2: 更大规模的 WeakReference(150万批/300万上限), referent 64B */
public class DemoRefProc2 {
    static final Deque<WeakReference<byte[]>> WEAKS = new ArrayDeque<>();
    static final int BATCH = 1_500_000;
    static final int CAP = 3_000_000;

    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 40; round++) {
            for (int i = 0; i < BATCH; i++) {
                WEAKS.add(new WeakReference<>(new byte[64]));
            }
            while (WEAKS.size() > CAP) WEAKS.poll();
            Thread.sleep(5);
        }
        System.out.println("done weaks=" + WEAKS.size());
    }
}
