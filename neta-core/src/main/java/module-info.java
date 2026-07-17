module net.hasor.neta {
    requires transitive jdk.net;
    requires transitive jdk.unsupported;
    requires transitive net.hasor.cobble;
    requires static jdk.sctp;
    requires static org.bouncycastle.pkix;
    requires static org.bouncycastle.provider;

    exports net.hasor.neta.bytebuf;
    exports net.hasor.neta.channel;
    exports net.hasor.neta.channel.data;
    exports net.hasor.neta.channel.routing;
    exports net.hasor.neta.channel.transport.quic;
    exports net.hasor.neta.channel.transport.sctp;
    exports net.hasor.neta.channel.transport.tcp;
    exports net.hasor.neta.channel.transport.udp;
    exports net.hasor.neta.channel.transport.virtual;
    exports net.hasor.neta.codec;
    exports net.hasor.neta.codec.ssl;
    exports net.hasor.neta.codec.string;
}
