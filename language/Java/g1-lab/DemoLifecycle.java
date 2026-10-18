import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Case A: 正常生命周期 young GC -> 并发标记 -> mixed GC(不含 humongous 干扰)
 * churn: 每轮 20 x 480KB(非 humongous) 短命对象; old: 每轮 512 x 2KB = 1MB 晋升;
 * 老年代达标后, 每轮随机抽掉 256 个长命对象 -> 老年代 Region 出现垃圾, mixed 才有得收。
 */
public class DemoLifecycle {
    static final List<byte[]> LIVE_OLD = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        long liveTarget = Long.parseLong(args.length > 0 ? args[0] : "140");
        int round = 0;
        while (round < 300) {
            round++;
            for (int i = 0; i < 20; i++) {
                byte[] garbage = new byte[480 * 1024];
                garbage[0] = (byte) i;
            }
            if (LIVE_OLD.size() < liveTarget * 512) {
                for (int i = 0; i < 512; i++) {
                    byte[] keep = new byte[2 * 1024];
                    keep[0] = (byte) round;
                    LIVE_OLD.add(keep);
                }
            } else {
                // O(1) swap-remove 制造老年代内的死对象
                for (int i = 0; i < 256 && LIVE_OLD.size() > 1024; i++) {
                    int idx = ThreadLocalRandom.current().nextInt(LIVE_OLD.size());
                    int last = LIVE_OLD.size() - 1;
                    LIVE_OLD.set(idx, LIVE_OLD.get(last));
                    LIVE_OLD.remove(last);
                }
            }
            Thread.sleep(15);
        }
        System.out.println("done, live-old-mb=" + LIVE_OLD.size() / 512);
    }
}
