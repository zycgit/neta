package net.hasor.neta.http;

import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.codec.http.cookie.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark: Cookie Encoding and Decoding.
 * Compares Neta cookie codec performance against Netty.
 */
@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class CookieBenchmark {

    // ========================= Test Data =========================

    // Simple cookie header (3 cookies)
    private static final String SIMPLE_COOKIE_HEADER = "session=abc123; lang=en; theme=dark";

    // Complex cookie header (8 cookies with various values)
    private static final String COMPLEX_COOKIE_HEADER = "JSESSIONID=2By8LOhBmaW5nZXJwcmludA; " + "_ga=GA1.2.1234567890.1234567890; " + "_gid=GA1.2.9876543210.9876543210; " + "csrf_token=a1b2c3d4e5f6g7h8i9j0; " + "user_pref=%7B%22color%22%3A%22blue%22%7D; " + "tracking_id=550e8400-e29b-41d4-a716-446655440000; " + "consent=analytics%3Dtrue%26marketing%3Dfalse; " + "ab_test=variant_b";

    // Set-Cookie header with all attributes
    private static final String SET_COOKIE_HEADER = "session=abc123; Domain=example.com; Path=/api; Max-Age=3600; Expires=Thu, 01 Jan 2099 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax";

    // Neta cookie objects for encoding
    private Cookie[]      netaSimpleCookies;
    private DefaultCookie netaFullCookie;

    // Netty cookie objects for encoding
    private io.netty.handler.codec.http.cookie.Cookie[]      nettySimpleCookies;
    private io.netty.handler.codec.http.cookie.DefaultCookie nettyFullCookie;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(CookieBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }

    // ========================= Request Cookie Decode =========================

    @Setup(Level.Trial)
    public void setup() {
        // Neta cookies
        netaSimpleCookies = new Cookie[] { new DefaultCookie("session", "abc123"), new DefaultCookie("lang", "en"), new DefaultCookie("theme", "dark") };

        netaFullCookie = new DefaultCookie("session", "abc123");
        netaFullCookie.setDomain("example.com");
        netaFullCookie.setPath("/api");
        netaFullCookie.setMaxAge(3600);
        netaFullCookie.setSecure(true);
        netaFullCookie.setHttpOnly(true);
        netaFullCookie.setSameSite("Lax");

        // Netty cookies
        nettySimpleCookies = new io.netty.handler.codec.http.cookie.Cookie[] { new io.netty.handler.codec.http.cookie.DefaultCookie("session", "abc123"), new io.netty.handler.codec.http.cookie.DefaultCookie("lang", "en"), new io.netty.handler.codec.http.cookie.DefaultCookie("theme", "dark") };

        nettyFullCookie = new io.netty.handler.codec.http.cookie.DefaultCookie("session", "abc123");
        nettyFullCookie.setDomain("example.com");
        nettyFullCookie.setPath("/api");
        nettyFullCookie.setMaxAge(3600);
        nettyFullCookie.setSecure(true);
        nettyFullCookie.setHttpOnly(true);
    }

    @Benchmark
    public List<Cookie> neta_decodeSimpleCookies() {
        return CookieDecoder.decode(SIMPLE_COOKIE_HEADER);
    }

    @Benchmark
    public java.util.Set<io.netty.handler.codec.http.cookie.Cookie> netty_decodeSimpleCookies() {
        return io.netty.handler.codec.http.cookie.ServerCookieDecoder.STRICT.decode(SIMPLE_COOKIE_HEADER);
    }

    @Benchmark
    public List<Cookie> neta_decodeComplexCookies() {
        return CookieDecoder.decode(COMPLEX_COOKIE_HEADER);
    }

    // ========================= Request Cookie Encode =========================

    @Benchmark
    public java.util.Set<io.netty.handler.codec.http.cookie.Cookie> netty_decodeComplexCookies() {
        return io.netty.handler.codec.http.cookie.ServerCookieDecoder.STRICT.decode(COMPLEX_COOKIE_HEADER);
    }

    @Benchmark
    public String neta_encodeSimpleCookies() {
        return CookieEncoder.encode(netaSimpleCookies);
    }

    // ========================= Set-Cookie Decode (Server) =========================

    @Benchmark
    public String netty_encodeSimpleCookies() {
        return io.netty.handler.codec.http.cookie.ClientCookieEncoder.STRICT.encode(nettySimpleCookies);
    }

    @Benchmark
    public DefaultCookie neta_decodeSetCookie() {
        return ServerCookieDecoder.decode(SET_COOKIE_HEADER);
    }

    // ========================= Set-Cookie Encode (Server) =========================

    @Benchmark
    public io.netty.handler.codec.http.cookie.Cookie netty_decodeSetCookie() {
        return io.netty.handler.codec.http.cookie.ClientCookieDecoder.STRICT.decode(SET_COOKIE_HEADER);
    }

    @Benchmark
    public String neta_encodeSetCookie() {
        return ServerCookieEncoder.encode(netaFullCookie);
    }

    // ========================= Main =========================

    @Benchmark
    public String netty_encodeSetCookie() {
        return io.netty.handler.codec.http.cookie.ServerCookieEncoder.STRICT.encode(nettyFullCookie);
    }
}
