/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http;
import java.util.List;
import java.util.Set;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpHeadersAppendTest extends AbstractHttpTest {
    private FullHttpRequest roundTripRequestOnSingleEndedPipes(NetManager neta, FullHttpRequest request) throws Throwable {
        VirtualPipe encoderPipe = openVirtualPipe(neta, ctx -> {
            ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        String encoded = text(sendAndOutBound(encoderPipe, request));

        VirtualPipe decoderPipe = openVirtualPipe(neta, ctx -> {
            ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            ctx.addLastDecoder("req-agg", new HttpRequestAggregator());
        }, VrtSoConfig.asServer());
        return (FullHttpRequest) receiveAndIntBound(decoderPipe, ascii(encoded)).get(0);
    }

    @Test
    public void testAppendDefaultHttpHeadersSurvivesSourceRelease() throws Throwable {
        autoCloseNeta(neta -> {
            DefaultHttpHeaders source = new DefaultHttpHeaders();
            source.addHeader("X-Test", "value-1");
            FullHttpRequest request = emptyFullRequestGet(HttpMethod.GET, "/append/default");
            request.appendHeaders(source);

            FullHttpRequest target = roundTripRequestOnSingleEndedPipes(neta, request);
            assertEquals("value-1", target.getString("X-Test"));
            assertTrue(target.containsHeader("x-test"));
        });
    }

    @Test
    public void testAppendDefaultHttpHeadersPreservesRepeatedValues() throws Throwable {
        autoCloseNeta(neta -> {
            FullHttpRequest request = emptyFullRequestGet(HttpMethod.GET, "/append/generic");
            DefaultHttpHeaders source = new DefaultHttpHeaders();
            source.addHeader("X-Test", "value-1");
            source.addHeader("X-Test", "value-2");
            source.addHeader("Content-Type", "text/plain");
            request.appendHeaders(source);

            FullHttpRequest target = roundTripRequestOnSingleEndedPipes(neta, request);
            assertEquals("value-1", target.getString("X-Test"));
            assertEquals(2, target.getValues("X-Test").size());
            assertEquals("text/plain", target.getString("Content-Type"));
        });
    }

    @Test
    public void testAppendHeadersPreservesDistinctHeaderNames() throws Throwable {
        autoCloseNeta(neta -> {
            FullHttpRequest request = emptyFullRequestGet(HttpMethod.GET, "/append/names");
            request.addHeader("Host", "example.com");
            DefaultHttpHeaders source = new DefaultHttpHeaders();
            source.addHeader("Host", "example.org");
            source.addHeader("Accept", "text/plain");
            request.appendHeaders(source);

            FullHttpRequest target = roundTripRequestOnSingleEndedPipes(neta, request);
            Set<String> names = target.headerNames();
            assertTrue(names.contains("Host"));
            assertTrue(names.contains("Accept"));

            List<String> hostValues = target.getValues("Host");
            assertEquals(2, hostValues.size());
            assertEquals("example.com", hostValues.get(0));
            assertEquals("example.org", hostValues.get(1));
        });
    }
}