import java.util.ArrayList;
import java.util.List;

/**
 * Case B: Humongous 对象(超过 Region 一半)的分配与回收
 * 1MB Region 时, 512KB 以上的对象就是 humongous; 这里用 600KB 的数组反复分配/释放。
 * 观察: Humongous regions: X->Y、"Pause Young ... (G1 Humongous Allocation)" 触发原因。
 */
public class DemoHumongous {
    static final List<byte[]> KEEP = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int keep = args.length > 0 ? Integer.parseInt(args[0]) : 0; // 常驻 humongous 数量(制造碎片)
        for (int i = 0; i < keep; i++) {
            KEEP.add(new byte[600 * 1024]);
        }
        byte[][] churn = new byte[16][];
        for (int round = 0; round < 4000; round++) {
            for (int i = 0; i < churn.length; i++) {
                churn[i] = new byte[600 * 1024]; // humongous: 600KB > 1MB/2
                churn[i][i] = (byte) round;
            }
            Thread.sleep(5);
        }
        System.out.println("done keep=" + KEEP.size());
    }
}
