package net.hasor.neta.codec.http;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.StringView;

public class HttpStringViewLifecycleTest extends AbstractHttpTest {
    @Test
    public void testHeaderEntryReadMaterializesAndReleasesStringView() {
        ByteBuf nameBuf = ascii("X-Test");
        ByteBuf valueBuf = ascii("value");
        try {
            DefaultHttpHeaderEntry entry = new DefaultHttpHeaderEntry(StringView.request(nameBuf, 0, 6), StringView.request(valueBuf, 0, 5));
            assertEquals(2, nameBuf.refCnt());
            assertEquals(2, valueBuf.refCnt());

            assertEquals("X-Test", entry.getName());
            assertEquals("value", entry.getValue());
            assertEquals(1, nameBuf.refCnt());
            assertEquals(1, valueBuf.refCnt());

            entry.release();
            assertEquals(1, nameBuf.refCnt());
            assertEquals(1, valueBuf.refCnt());
        } finally {
            nameBuf.release();
            valueBuf.release();
        }
    }

    @Test
    public void testRequestUriReadMaterializesAndReleasesStringView() {
        ByteBuf uriBuf = ascii("/hello");
        try {
            DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, StringView.request(uriBuf, 0, 6));
            assertEquals(2, uriBuf.refCnt());

            assertEquals("/hello", request.uri());
            assertEquals(1, uriBuf.refCnt());

            request.release();
            assertEquals(1, uriBuf.refCnt());
        } finally {
            uriBuf.release();
        }
    }

    @Test
    public void testAppendHeadersMaterializesSourceEntriesBeforeRelease() {
        ByteBuf nameBuf = ascii("X-Test");
        ByteBuf valueBuf = ascii("value");
        try {
            DefaultHttpHeaders source = new DefaultHttpHeaders();
            source.addHeaderEntry(new DefaultHttpHeaderEntry(StringView.request(nameBuf, 0, 6), StringView.request(valueBuf, 0, 5)));
            DefaultHttpHeaders target = new DefaultHttpHeaders();

            target.appendHeaders(source);
            source.release();

            assertEquals(1, nameBuf.refCnt());
            assertEquals(1, valueBuf.refCnt());
            assertEquals("value", target.getString("x-test"));

            target.release();
            assertEquals(1, nameBuf.refCnt());
            assertEquals(1, valueBuf.refCnt());
        } finally {
            nameBuf.release();
            valueBuf.release();
        }
    }
}