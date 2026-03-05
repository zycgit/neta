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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.logging.Logger;

/**
 * Handles PATH_CHALLENGE and PATH_RESPONSE frames for path validation and migration per RFC 9000 §8.2.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicPathValidator {
    private static final Logger       logger = Logger.getLogger(QuicPathValidator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Maximum time to wait for a PATH_RESPONSE before considering validation failed (ms). */
    private static final long VALIDATION_TIMEOUT_MS = 3000;

    /** Pending path validations: challenge data → timestamp. */
    private final    Map<ChallengeKey, Long> pendingChallenges    = new ConcurrentHashMap<>();
    /** Whether the current path has been validated. */
    private volatile boolean                 currentPathValidated = true;
    // ── Anti-amplification (RFC 9000 §9.3.1) ───────────────────────────────────
    private volatile boolean                 antAmpActive         = false; // true while new path is unvalidated
    private volatile long                    antAmpBytesIn        = 0;     // bytes received on unvalidated path
    private volatile long                    antAmpBytesSent      = 0;     // bytes sent on unvalidated path
    // ── Migration callbacks ─────────────────────────────────────────────────
    private volatile Runnable                onTimeoutCallback    = null;  // called on PATH_CHALLENGE timeout
    private volatile BasicFuture<Long>       validationFuture     = null;  // completes with RTT on validation

    /** Builds a PATH_CHALLENGE frame (RFC 9000 §19.17). */
    static byte[] buildPathChallengeFrame(byte[] data) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_CHALLENGE);
        byte[] frame = new byte[typeBytes.length + 8];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(data, 0, frame, typeBytes.length, 8);
        return frame;
    }

    /** Builds a PATH_RESPONSE frame (RFC 9000 §19.18). */
    static byte[] buildPathResponseFrame(byte[] data) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_RESPONSE);
        byte[] frame = new byte[typeBytes.length + 8];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(data, 0, frame, typeBytes.length, 8);
        return frame;
    }

    /**
     * Initiates a path validation by generating a PATH_CHALLENGE frame.
     * @return the complete PATH_CHALLENGE frame bytes
     */
    byte[] initiateChallenge() {
        return initiateChallenge(null);
    }

    /** Initiates a path validation with an optional future that completes with RTT on success or TimeoutException on timeout. */
    byte[] initiateChallenge(BasicFuture<Long> completionFuture) {
        byte[] challengeData = new byte[8];
        RANDOM.nextBytes(challengeData);
        this.pendingChallenges.put(new ChallengeKey(challengeData), System.currentTimeMillis());
        this.currentPathValidated = false;
        this.validationFuture = completionFuture;
        logger.info("Initiating path challenge: " + QuicCrypto.bytesToHex(challengeData));
        return buildPathChallengeFrame(challengeData);
    }

    /** Activates anti-amplification mode on migration start and registers a timeout callback (RFC 9000 §9.3.1). */
    void onMigrationStart(Runnable onTimeout) {
        this.antAmpActive = true;
        this.antAmpBytesIn = 0;
        this.antAmpBytesSent = 0;
        this.onTimeoutCallback = onTimeout;
        this.currentPathValidated = false;
    }

    /** Records incoming bytes on the unvalidated path for anti-amplification accounting. */
    void recordIncoming(int bytes) {
        if (this.antAmpActive) {
            this.antAmpBytesIn += bytes;
        }
    }

    /** Returns true if anti-amplification limits allow sending the given number of bytes on the current path. */
    boolean canSendBytes(int bytes) {
        if (!this.antAmpActive) {
            return true;
        }
        return (this.antAmpBytesIn * 3L) >= (this.antAmpBytesSent + bytes);
    }

    /** Records bytes sent on the unvalidated path (for anti-amplification accounting). */
    void recordOutgoing(int bytes) {
        if (this.antAmpActive) {
            this.antAmpBytesSent += bytes;
        }
    }

    /** Processes a PATH_RESPONSE frame; returns true if it matches a pending challenge and the path is validated. */
    boolean onPathResponse(byte[] responseData) {
        if (responseData == null || responseData.length != 8) {
            return false;
        }
        ChallengeKey key = new ChallengeKey(responseData);
        Long sentTime = this.pendingChallenges.remove(key);
        if (sentTime != null) {
            this.currentPathValidated = true;
            // Clear anti-amplification state
            this.antAmpActive = false;
            this.onTimeoutCallback = null;
            long rtt = System.currentTimeMillis() - sentTime;
            logger.info("Path validated (rtt=" + rtt + "ms)");
            // Complete any pending migration future
            BasicFuture<Long> f = this.validationFuture;
            if (f != null) {
                this.validationFuture = null;
                f.completed(rtt);
            }
            return true;
        }
        return false;
    }

    /** Processes a PATH_CHALLENGE frame from the peer; returns the PATH_RESPONSE frame bytes to send back. */
    byte[] onPathChallenge(byte[] challengeData) {
        if (challengeData == null || challengeData.length != 8) {
            return null;
        }
        return buildPathResponseFrame(challengeData);
    }

    /** Removes timed-out pending challenges and fails any migration future; returns true if any challenge timed out. */
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
            // Fail any pending migration future
            BasicFuture<Long> f = this.validationFuture;
            if (f != null) {
                this.validationFuture = null;
                f.failed(new TimeoutException("PATH_CHALLENGE timed out after " + VALIDATION_TIMEOUT_MS + "ms"));
            }
            // Invoke the timeout callback (e.g., close connection on migration timeout)
            Runnable cb = this.onTimeoutCallback;
            if (cb != null) {
                this.onTimeoutCallback = null;
                cb.run();
            }
        }
        return anyTimeout;
    }

    // ── Frame builders ─────────────────────────────────────────────────

    /** Returns whether the current path has been validated. */
    boolean isCurrentPathValidated() {
        return this.currentPathValidated;
    }

    /** Returns the number of pending (unanswered) challenges. */
    int getPendingCount() {
        return this.pendingChallenges.size();
    }

    /** Key wrapper for 8-byte challenge data. */
    private static final class ChallengeKey {
        final byte[] data;
        final int    hash;

        ChallengeKey(byte[] data) {
            this.data = data;
            this.hash = Arrays.hashCode(data);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

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
