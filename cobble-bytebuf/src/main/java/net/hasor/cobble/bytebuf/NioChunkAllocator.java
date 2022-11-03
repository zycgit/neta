package net.hasor.cobble.bytebuf;
interface NioChunkAllocator {
    NioChunk allocateBuffer(int capacity);
}
