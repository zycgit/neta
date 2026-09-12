/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.logging.Logger;
/**
 * Handles PATH_CHALLENGE and PATH_RESPONSE frames for path validation and migration.
 * <p>This corresponds to RFC 9000 Section 8.2.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicPathValidator {
    private static final Logger       logger = Logger.getLogger(QuicPathValidator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Maximum wait time for PATH_RESPONSE in milliseconds; exceeding it means validation failure. */
    private static final long VALIDATION_TIMEOUT_MS = 3000;

    /** Pending path validations, keyed by challenge data with the send timestamp as the value. */
    private final Map<ChallengeKey, Long> pendingChallenges    = new ConcurrentHashMap<>();
    /** Whether the current path has already been validated. */
    private volatile boolean              currentPathValidated = true;
    // ── Anti-amplification limit (RFC 9000 §9.3.1) ───────────────────
    private volatile boolean antAmpActive    = false; // True while the new path has not been validated yet
    private volatile long    antAmpBytesIn   = 0;     // Bytes received on the unvalidated path
    private volatile long    antAmpBytesSent = 0;     // Bytes sent on the unvalidated path
    // ── Migration callbacks ───────────────────────────────────────────
    private volatile Runnable          onTimeoutCallback = null;  // Callback invoked when PATH_CHALLENGE times out
    private volatile BasicFuture<Long> validationFuture  = null;  // Completed with RTT when validation succeeds

    /**
     * Builds a PATH_CHALLENGE frame.
     */
    static byte[] buildPathChallengeFrame(byte[] data) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_CHALLENGE);
        byte[] frame = new byte[typeBytes.length + 8];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(data, 0, frame, typeBytes.length, 8);
        return frame;
    }

    /**
     * Builds a PATH_RESPONSE frame.
     */
    static byte[] buildPathResponseFrame(byte[] data) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_RESPONSE);
        byte[] frame = new byte[typeBytes.length + 8];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(data, 0, frame, typeBytes.length, 8);
        return frame;
    }

    /**
     * Starts a path validation attempt.
     * @return returns the full PATH_CHALLENGE frame bytes
     */
    byte[] initiateChallenge() {
        return initiateChallenge(null);
    }

    /**
     * Starts a path validation attempt and optionally binds a completion future.
     */
    byte[] initiateChallenge(BasicFuture<Long> completionFuture) {
        byte[] challengeData = new byte[8];
        RANDOM.nextBytes(challengeData);
        this.pendingChallenges.put(new ChallengeKey(challengeData), System.currentTimeMillis());
        this.currentPathValidated = false;
        this.validationFuture = completionFuture;
        logger.info("Initiating path challenge: " + QuicCrypto.bytesToHex(challengeData));
        return buildPathChallengeFrame(challengeData);
    }

    /**
     * Enables anti-amplification limits when path migration starts and registers a timeout callback.
     */
    void onMigrationStart(Runnable onTimeout) {
        this.antAmpActive = true;
        this.antAmpBytesIn = 0;
        this.antAmpBytesSent = 0;
        this.onTimeoutCallback = onTimeout;
        this.currentPathValidated = false;
    }

    /**
     * Records incoming bytes on an unvalidated path.
     */
    void recordIncoming(int bytes) {
        if (this.antAmpActive) {
            this.antAmpBytesIn += bytes;
        }
    }

    /**
     * Returns whether sending the given number of bytes is still allowed under the anti-amplification limit.
     */
    boolean canSendBytes(int bytes) {
        if (!this.antAmpActive) {
            return true;
        }
        return (this.antAmpBytesIn * 3L) >= (this.antAmpBytesSent + bytes);
    }

    /**
     * Records outgoing bytes on an unvalidated path.
     */
    void recordOutgoing(int bytes) {
        if (this.antAmpActive) {
            this.antAmpBytesSent += bytes;
        }
    }

    /**
     * Processes a received PATH_RESPONSE frame.
     * @return returns true if the response matches a pending challenge and path validation succeeds
     */
    boolean onPathResponse(byte[] responseData) {
        if (responseData == null || responseData.length != 8) {
            return false;
        }
        ChallengeKey key = new ChallengeKey(responseData);
        Long sentTime = this.pendingChallenges.remove(key);
        if (sentTime != null) {
            this.currentPathValidated = true;
            // Clear anti-amplification-related state.
            this.antAmpActive = false;
            this.onTimeoutCallback = null;
            long rtt = System.currentTimeMillis() - sentTime;
            logger.info("Path validated (rtt=" + rtt + "ms)");
            // Complete the pending path-validation future.
            BasicFuture<Long> f = this.validationFuture;
            if (f != null) {
                this.validationFuture = null;
                f.completed(rtt);
            }
            return true;
        }
        return false;
    }

    /**
     * Processes a PATH_CHALLENGE frame received from the peer.
     * @return returns the PATH_RESPONSE frame bytes that should be sent back
     */
    byte[] onPathChallenge(byte[] challengeData) {
        if (challengeData == null || challengeData.length != 8) {
            return null;
        }
        return buildPathResponseFrame(challengeData);
    }

    /**
     * Cleans up expired challenges and fails the corresponding migration future.
     * @return returns true if any challenge timed out
     */
    boolean checkTimeouts() {
        long now = System.currentTimeMillis();
        boolean anyTimeout = false;
        for (Map.Entry<ChallengeKey, Long> entry : this.pendingChallenges.entrySet()) {
            if (now - entry.getValue() > VALIDATION_TIMEOUT_MS) {
                this.pendingChallenges.remove(entry.getKey());
                anyTimeout = true;
                logger.warn("Path challenge timed out after " + (now - entry.getValue()) + "ms");
            }
        }
        if (anyTimeout) {
            // Fail the pending migration future.
            BasicFuture<Long> f = this.validationFuture;
            if (f != null) {
                this.validationFuture = null;
                f.failed(new TimeoutException("PATH_CHALLENGE timed out after " + VALIDATION_TIMEOUT_MS + "ms"));
            }
            // Invoke the timeout callback, for example to close the connection on migration timeout.
            Runnable cb = this.onTimeoutCallback;
            if (cb != null) {
                this.onTimeoutCallback = null;
                cb.run();
            }
        }
        return anyTimeout;
    }

    // ── State queries ─────────────────────────────────────────────────

    /**
     * Returns whether the current path has been validated.
     */
    boolean isCurrentPathValidated() {
        return this.currentPathValidated;
    }

    /**
     * Returns the number of challenges currently pending.
     */
    int getPendingCount() {
        return this.pendingChallenges.size();
    }

    /**
     * Key wrapper for 8-byte challenge data.
     */
    private static final class ChallengeKey {
        final byte[] data;
        final int    hash;

        /**
         * Creates a key object from challenge data.
         */
        ChallengeKey(byte[] data) {
            this.data = data;
            this.hash = Arrays.hashCode(data);
        }

        /**
         * Returns the cached hash value.
         */
        @Override
        public int hashCode() {
            return this.hash;
        }

        /**
         * Returns whether two challenge keys represent the same challenge data.
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ChallengeKey)) {
                return false;
            }
            return Arrays.equals(this.data, ((ChallengeKey) obj).data);
        }
    }
}
