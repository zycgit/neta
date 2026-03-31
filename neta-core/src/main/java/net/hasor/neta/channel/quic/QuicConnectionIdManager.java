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
package net.hasor.neta.channel.quic;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.logging.Logger;

/**
 * Manages Connection ID rotation and lifecycle.
 * <p>This corresponds to RFC 9000 Section 5.1 and covers NEW_CONNECTION_ID and RETIRE_CONNECTION_ID handling.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicConnectionIdManager {
    private static final Logger       logger = Logger.getLogger(QuicConnectionIdManager.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Locally issued CIDs indexed by sequence number. */
    private final    ConcurrentHashMap<Long, CidEntry> localCids           = new ConcurrentHashMap<>();
    /** Peer-issued CIDs indexed by sequence number. */
    private final    ConcurrentHashMap<Long, CidEntry> remoteCids          = new ConcurrentHashMap<>();
    /** Next local CID sequence number. */
    private final    AtomicLong                        nextLocalSeq        = new AtomicLong(1); // 0 is the initial CID
    /** Next peer CID sequence number. */
    private final    AtomicLong                        nextRemoteSeq       = new AtomicLong(1);
    /** Connection ID length in bytes. */
    private final    int                               cidLength;
    /** Largest Retire Prior To value sent locally. */
    private final    long                              localRetirePriorTo  = 0;
    /** Largest Retire Prior To value received from the peer. */
    private volatile long                              remoteRetirePriorTo = 0;
    /** Active Connection ID Limit advertised by the peer. */
    private volatile int                               peerActiveLimit     = 2;
    /** Sequence number of the active peer CID currently used on the sending path. */
    private volatile long                              activeRemoteCidSeq  = 0;

    /**
     * Creates the manager from the initial local and peer CIDs.
     */
    QuicConnectionIdManager(byte[] localCid, byte[] remoteCid, int cidLength) {
        this.cidLength = cidLength;
        // Register the initial CID under sequence number 0.
        CidEntry localEntry = new CidEntry(0, localCid, generateResetToken());
        this.localCids.put(0L, localEntry);
        if (remoteCid != null && remoteCid.length > 0) {
            CidEntry remoteEntry = new CidEntry(0, remoteCid, null);
            this.remoteCids.put(0L, remoteEntry);
        }
    }

    /**
     * Builds a NEW_CONNECTION_ID frame.
     */
    static byte[] buildNewConnectionIdFrame(long seqNum, long retirePriorTo, byte[] cid, byte[] resetToken) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.NEW_CONNECTION_ID);
        byte[] seqBytes = QuicVarInt.encode(seqNum);
        byte[] retireBytes = QuicVarInt.encode(retirePriorTo);
        // cidLen is encoded in a single byte.
        int totalLen = typeBytes.length + seqBytes.length + retireBytes.length + 1 + cid.length + 16;
        byte[] frame = new byte[totalLen];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(seqBytes, 0, frame, pos, seqBytes.length);
        pos += seqBytes.length;
        System.arraycopy(retireBytes, 0, frame, pos, retireBytes.length);
        pos += retireBytes.length;
        frame[pos++] = (byte) cid.length;
        System.arraycopy(cid, 0, frame, pos, cid.length);
        pos += cid.length;
        if (resetToken != null && resetToken.length == 16) {
            System.arraycopy(resetToken, 0, frame, pos, 16);
        }
        return frame;
    }

    /**
     * Builds a RETIRE_CONNECTION_ID frame.
     */
    static byte[] buildRetireConnectionIdFrame(long seqNum) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.RETIRE_CONNECTION_ID);
        byte[] seqBytes = QuicVarInt.encode(seqNum);
        byte[] frame = new byte[typeBytes.length + seqBytes.length];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(seqBytes, 0, frame, typeBytes.length, seqBytes.length);
        return frame;
    }

    /**
     * Generates a stateless reset token.
     */
    private static byte[] generateResetToken() {
        byte[] token = new byte[16];
        RANDOM.nextBytes(token);
        return token;
    }

    /**
     * Sets the Active Connection ID Limit advertised by the peer.
     */
    void setPeerActiveLimit(int limit) {
        this.peerActiveLimit = Math.max(limit, 2);
    }

    /**
     * Issues a new local Connection ID.
     * @return returns null if doing so would exceed the peer limit; otherwise returns the matching NEW_CONNECTION_ID frame
     */
    byte[] issueNewConnectionId() {
        long activeCount = countActiveLocalCids();
        if (activeCount >= this.peerActiveLimit) {
            return null; // This would exceed the peer limit.
        }

        long seq = this.nextLocalSeq.getAndIncrement();
        byte[] newCid = new byte[this.cidLength];
        RANDOM.nextBytes(newCid);
        byte[] resetToken = generateResetToken();

        CidEntry entry = new CidEntry(seq, newCid, resetToken);
        this.localCids.put(seq, entry);

        logger.info("Issued new local CID seq=" + seq + ", cid=" + QuicCrypto.bytesToHex(newCid));
        return buildNewConnectionIdFrame(seq, this.localRetirePriorTo, newCid, resetToken);
    }

    /**
     * Processes a received NEW_CONNECTION_ID frame.
     * @return list of RETIRE_CONNECTION_ID frames that should be sent back
     */
    List<byte[]> onNewConnectionId(long sequenceNumber, long retirePriorTo, byte[] connectionId, byte[] resetToken) {
        List<byte[]> retireFrames = new ArrayList<>();

        // Store the new peer CID.
        CidEntry entry = new CidEntry(sequenceNumber, connectionId, resetToken);
        this.remoteCids.put(sequenceNumber, entry);

        // Update retirePriorTo.
        if (retirePriorTo > this.remoteRetirePriorTo) {
            this.remoteRetirePriorTo = retirePriorTo;
            // Retire all CIDs with sequence numbers lower than retirePriorTo.
            for (Long seq : new ArrayList<>(this.remoteCids.keySet())) {
                if (seq < retirePriorTo) {
                    this.remoteCids.remove(seq);
                    retireFrames.add(buildRetireConnectionIdFrame(seq));
                    logger.info("Retiring remote CID seq=" + seq);
                    // If the current active CID was retired, switch to a new one.
                    if (seq == this.activeRemoteCidSeq) {
                        switchToNextRemoteCid();
                    }
                }
            }
        }

        return retireFrames;
    }

    /**
     * Processes a received RETIRE_CONNECTION_ID frame.
     * @return returns a replacement NEW_CONNECTION_ID frame if a new CID should be issued
     */
    byte[] onRetireConnectionId(long sequenceNumber) {
        CidEntry removed = this.localCids.remove(sequenceNumber);
        if (removed != null) {
            logger.info("Local CID seq=" + sequenceNumber + " retired by peer");
            // Issue a replacement CID.
            return issueNewConnectionId();
        }
        return null;
    }

    /**
     * Returns the active peer CID currently used when sending packets.
     */
    byte[] getActiveRemoteCid() {
        CidEntry entry = this.remoteCids.get(this.activeRemoteCidSeq);
        return (entry != null) ? entry.cid : null;
    }

    /**
     * Rotates to the next available peer CID.
     * @return returns the new CID, or null if none is available
     */
    byte[] rotateRemoteCid() {
        switchToNextRemoteCid();
        return getActiveRemoteCid();
    }

    // ── Frame-related helpers ─────────────────────────────────────────

    /**
     * Finds the matching stateless reset token for a peer CID.
     */
    byte[] findResetToken(byte[] cid) {
        for (CidEntry entry : this.remoteCids.values()) {
            if (java.util.Arrays.equals(entry.cid, cid)) {
                return entry.resetToken;
            }
        }
        return null;
    }

    /**
     * Returns whether the given 16-byte token matches any known peer CID stateless reset token.
     */
    boolean isStatelessReset(byte[] resetToken) {
        if (resetToken == null || resetToken.length != 16) {
            return false;
        }
        for (CidEntry entry : this.remoteCids.values()) {
            if (entry.resetToken != null && java.util.Arrays.equals(entry.resetToken, resetToken)) {
                return true;
            }
        }
        return false;
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Returns all currently active local CIDs for connection lookup.
     */
    List<byte[]> getActiveLocalCids() {
        List<byte[]> result = new ArrayList<>();
        for (CidEntry entry : this.localCids.values()) {
            result.add(entry.cid);
        }
        return result;
    }

    /**
     * Counts the number of local CIDs that are still active.
     */
    private long countActiveLocalCids() {
        long count = 0;
        for (Long seq : this.localCids.keySet()) {
            if (seq >= this.localRetirePriorTo) {
                count++;
            }
        }
        return count;
    }

    /**
     * Switches to the next available peer CID.
     */
    private void switchToNextRemoteCid() {
        long current = this.activeRemoteCidSeq;
        for (Long seq : this.remoteCids.keySet()) {
            if (seq > current && seq >= this.remoteRetirePriorTo) {
                this.activeRemoteCidSeq = seq;
                return;
            }
        }
        // If no higher sequence number exists, try any other CID that is still valid.
        for (Long seq : this.remoteCids.keySet()) {
            if (seq >= this.remoteRetirePriorTo && seq != current) {
                this.activeRemoteCidSeq = seq;
                return;
            }
        }
    }

    /**
     * CID entry.
     */
    static class CidEntry {
        final long   sequenceNumber;
        final byte[] cid;
        final byte[] resetToken;

        CidEntry(long sequenceNumber, byte[] cid, byte[] resetToken) {
            this.sequenceNumber = sequenceNumber;
            this.cid = cid;
            this.resetToken = resetToken;
        }
    }
}
