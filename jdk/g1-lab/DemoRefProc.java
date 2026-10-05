import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Case E: 引用对象(WeakReference)数量巨大时 Ref Proc 的代价
 * 维护 120 万个 WeakReference; 其 referent(256B) 分配后立刻不可达,
 * 每次Young GC 都要发现/处理上百万个引用 -> "Pre Evacuate Collection Set"(引用处理所在阶段)被拉长。
 */
public class DemoRefProc {
    static final Deque<WeakReference<byte[]>> WEAKS = new ArrayDeque<>();
    static final int BATCH = 150_000;
    static final int CAP = 1_200_000;

    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 80; round++) {
            for (int i = 0; i < BATCH; i++) {
                WEAKS.add(new WeakReference<>(new byte[256]));
            }
            while (WEAKS.size() > CAP) {
                WEAKS.poll(); // 只丢引用对象本身, referent 早已是垃圾
            }
            Thread.sleep(5);
        }
        System.out.println("done weaks=" + WEAKS.size());
    }
}
