import java.util.ArrayList;
import java.util.List;

/**
 * Case C: to-space exhausted(拷贝失败) -> 自动 Full GC
 * 小堆 + 大量存活对象塞满老年代, 再快速分配, 迫使 young GC 在 CSet 拷贝时找不到 to/old 空间。
 * 观察: "[gc] G1 Evacuation Pause" 里出现 "to-space exhausted", 紧跟 "Pause Full (G1 Compaction Pause)"。
 */
public class DemoEvacFailure {
    static final List<byte[][]> OLD = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // 先把老年代塞到接近满: 每次留一半, 分代晋升
        long round = 0;
        try {
            while (true) {
                round++;
                byte[][] batch = new byte[64][];
                for (int i = 0; i < 64; i++) {
                    batch[i] = new byte[128 * 1024];
                    batch[i][i] = (byte) round;
                }
                // 常驻: 把 batch 藏进 OLD(部分丢弃制造 churn)
                if (round % 2 == 1) {
                    OLD.add(batch);
                }
                if (OLD.size() > 4096) OLD.subList(0, 1024).clear();
            }
        } catch (OutOfMemoryError e) {
            System.out.println("OOM at round=" + round + " old-batches=" + OLD.size());
        }
    }
}
