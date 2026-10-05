import java.util.ArrayList;
import java.util.List;

/**
 * Case D: 干净的显式 System.gc() 触发 Full GC
 * 堆里只有一份 24MB 的非 humongous 存活数据, 排除其他干扰。
 */
public class DemoSystemGc {
    public static void main(String[] args) throws Exception {
        List<byte[]> keep = new ArrayList<>();
        for (int i = 0; i < 48; i++) keep.add(new byte[512 * 1024]);
        Thread.sleep(500);
        System.gc(); // 显式触发
        Thread.sleep(500);
        System.out.println("done");
    }
}
