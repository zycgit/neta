module net.hasor.neta.handler.codec {
    requires transitive jdk.net;
    requires transitive jdk.unsupported;
    requires transitive net.hasor.neta;

    exports net.hasor.neta.codec.http;
    exports net.hasor.neta.codec.http.constant;
}