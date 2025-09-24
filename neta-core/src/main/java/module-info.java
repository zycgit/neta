module net.hasor.neta {
    requires jdk.net;
    requires jdk.unsupported;
    requires net.hasor.cobble;

    exports net.hasor.neta.bytebuf;
    exports net.hasor.neta.handler;
    exports net.hasor.neta.channel;
    exports net.hasor.neta.channel.tcp;
    exports net.hasor.neta.channel.udp;
    exports net.hasor.neta.channel.virtual;
}