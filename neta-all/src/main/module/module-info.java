module net.hasor.neta {
    requires transitive jdk.net;
    requires transitive jdk.unsupported;
    requires transitive net.hasor.cobble;

    // for neta-core
    exports net.hasor.neta.bytebuf;
    exports net.hasor.neta.handler;
    exports net.hasor.neta.channel;
    exports net.hasor.neta.channel.tcp;
    exports net.hasor.neta.channel.udp;
    exports net.hasor.neta.channel.virtual;

    // for neta-codec
    exports net.hasor.neta.handler.codec;
    exports net.hasor.neta.handler.codec.ssl;
    exports net.hasor.neta.handler.codec.string;
}