module net.hasor.neta.handler.codec {
    requires transitive jdk.net;
    requires transitive jdk.unsupported;
    requires transitive net.hasor.neta;

    exports net.hasor.neta.codec.http;
    exports net.hasor.neta.codec.http.cookie
    exports net.hasor.neta.codec.http.cors
    exports net.hasor.neta.codec.http.h2
    exports net.hasor.neta.codec.http.h3
    exports net.hasor.neta.codec.http.multipart
    exports net.hasor.neta.codec.http.routing
    exports net.hasor.neta.codec.http.websocket
}