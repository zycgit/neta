package net.hasor.neta.http;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;

/**
 * Minimal ProtoContext stub for JMH benchmarks.
 * Only byteBufAllocator() is used by HTTP/WebSocket encoders and decoders.
 */
public class StubProtoContext implements ProtoContext {
    public static final StubProtoContext INSTANCE = new StubProtoContext();

    @Override
    public ByteBufAllocator byteBufAllocator() {
        return ByteBufAllocator.DEFAULT;
    }

    @Override
    public NetConfig getConfig() {
        return null;
    }

    @Override
    public SoChannel<?> getChannel() {
        return null;
    }

    @Override
    public SoContext getSoContext() {
        return null;
    }

    @Override
    public String getStackName() {
        return null;
    }

    @Override
    public String findNextStack(String withName) {
        return null;
    }

    @Override
    public String findPreviousStack(String withName) {
        return null;
    }

    @Override
    public <T> T context(Class<T> attachment) {
        return null;
    }

    @Override
    public <T> T context(Class<T> attachmentType, T attachment) {
        return null;
    }

    @Override
    public <T> T flash(String key) {
        return null;
    }

    @Override
    public <T> T flash(String key, T flash) {
        return null;
    }

    @Override
    public Future<?> sendData(Object writeData) {
        return null;
    }

    @Override
    public <T> void fireUserEvent(Class<T> eventType, T event) {
    }

    @Override
    public Future<?> flush() {
        return null;
    }

    @Override
    public boolean isRcv() {
        return true;
    }

    @Override
    public boolean isSnd() {
        return false;
    }

    @Override
    public void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addFirst(ProtoDuplexer<?, ?, ?, ?> duplexer) {
    }

    @Override
    public void addFirst(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
    }

    @Override
    public void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addLast(ProtoDuplexer<?, ?, ?, ?> duplexer) {
    }

    @Override
    public void addLast(String name, ProtoDuplexer<?, ?, ?, ?> duplexer) {
    }

    @Override
    public void addFirstEncoder(ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addFirstEncoder(String name, ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addLastEncoder(ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addLastEncoder(String name, ProtoHandler<?, ?> encoder) {
    }

    @Override
    public void addFirstDecoder(ProtoHandler<?, ?> decoder) {
    }

    @Override
    public void addFirstDecoder(String name, ProtoHandler<?, ?> decoder) {
    }

    @Override
    public void addLastDecoder(ProtoHandler<?, ?> decoder) {
    }

    @Override
    public void addLastDecoder(String name, ProtoHandler<?, ?> decoder) {
    }
}
