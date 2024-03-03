package net.hasor.neta.bytebuf;
import org.junit.Test;

public class BufferRingTest {
    @Test
    public void ringTest_01() {
        BufferRing<String> ring = new BufferRing<>();

        assert ring.size() == 0;
        assert ring.next() == null;
        assert ring.next() == null;
        assert ring.next(0) == null;
        assert ring.next(0) == null;
        assert ring.next(10) == null;
        assert ring.next(10) == null;
    }

    @Test
    public void ringTest_02() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("abc");

        assert ring.size() == 1;
        assert ring.next().equals("abc");
        assert ring.next().equals("abc");
        assert ring.next(0).equals("abc");
        assert ring.next(0).equals("abc");
        assert ring.next(10).equals("abc");
        assert ring.next(10).equals("abc");
    }

    @Test
    public void ringTest_03() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");

        assert ring.size() == 2;
        assert ring.next().equals("a");
        assert ring.next().equals("b");
        assert ring.next(0).equals("b");
        assert ring.next(0).equals("b");
        assert ring.next(1).equals("a");
        assert ring.next(1).equals("a");
        assert ring.next(2).equals("b");
        assert ring.next(2).equals("b");
        assert ring.next(3).equals("a");
        assert ring.next(3).equals("a");
    }

    @Test
    public void ringTest_04() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.add("c");
        ring.add("d");
        ring.add("e");

        assert ring.size() == 5;
        assert ring.next().equals("a");
        assert ring.next().equals("b");
        assert ring.next().equals("c");
        assert ring.next().equals("d");
        assert ring.next().equals("e");

        ring.remove("c");
        assert ring.size() == 4;
        assert ring.next().equals("a");
        assert ring.next().equals("b");
        assert ring.next().equals("d");
        assert ring.next().equals("e");

        ring.remove("d");
        assert ring.size() == 3;
        assert ring.next().equals("b");
        assert ring.next().equals("e");
        assert ring.next().equals("a");

        ring.remove("a");
        assert ring.size() == 2;
        assert ring.next().equals("e");
        assert ring.next().equals("b");

        ring.remove("b");
        assert ring.size() == 1;
        assert ring.next().equals("e");

        ring.remove("e");
        assert ring.size() == 0;
        assert ring.next() == null;
    }
}