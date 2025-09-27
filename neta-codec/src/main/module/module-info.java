module net.hasor.neta.handler.codec {
    requires transitive jdk.net;
    requires transitive jdk.unsupported;
    requires transitive net.hasor.neta;

    exports net.hasor.neta.handler.codec;
    exports net.hasor.neta.handler.codec.ssl;
    exports net.hasor.neta.handler.codec.string;
}