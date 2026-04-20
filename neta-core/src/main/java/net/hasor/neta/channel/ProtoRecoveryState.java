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
package net.hasor.neta.channel;
import java.util.LinkedHashMap;
import java.util.Map;
final class ProtoRecoveryState {
    static final int            RECOVERY_RCV = 1;
    static final int            RECOVERY_SND = 1 << 1;
    private String              recoveryOwnerId;
    private String              recoveryBranchName;
    private boolean             pendingRcvRecoveryGlobal;
    private boolean             pendingSndRecoveryGlobal;
    private boolean             activeRcvRecoveryGlobal;
    private boolean             activeSndRecoveryGlobal;
    private Map<String, String> pendingRcvRecoveryBranches;
    private Map<String, String> pendingSndRecoveryBranches;
    private Map<String, String> activeRcvRecoveryBranches;
    private Map<String, String> activeSndRecoveryBranches;

    void setupSource(String ownerId, String branchName) {
        this.recoveryOwnerId = ownerId;
        this.recoveryBranchName = branchName;
    }

    void registerRecovery(ProtoRecoveryState sourceState, boolean isRcv) {
        if (sourceState.recoveryOwnerId == null || sourceState.recoveryBranchName == null) {
            this.setPendingRecoveryGlobal(isRcv, true);
            return;
        }

        this.branches(isRcv, false).put(sourceState.recoveryOwnerId, sourceState.recoveryBranchName);
    }

    int beginRecovery() {
        return this.beginDirectionRecovery(true) | this.beginDirectionRecovery(false);
    }

    void endRecovery() {
        this.clearActiveDirectionRecovery(true);
        this.clearActiveDirectionRecovery(false);
    }

    //

    boolean hasRecovery() {
        return this.hasDirectionRecovery(true) || this.hasDirectionRecovery(false);
    }

    boolean hasRecovery(boolean isRcv, String ownerId, String branchName) {
        if (ownerId == null || branchName == null) {
            return this.hasRecoveryGlobal(isRcv) || this.activeRecoveryGlobal(isRcv);
        }

        String pendingBranch = this.branches(isRcv, false).get(ownerId);
        if (branchName.equals(pendingBranch)) {
            return true;
        }

        String activeBranch = this.branches(isRcv, true).get(ownerId);
        return branchName.equals(activeBranch);
    }

    String activeRecovery(boolean isRcv, String ownerId) {
        return this.branches(isRcv, true).get(ownerId);
    }

    //

    private boolean hasDirectionRecovery(boolean isRcv) {
        return this.hasRecoveryGlobal(isRcv) || !this.branches(isRcv, false).isEmpty();
    }

    private int beginDirectionRecovery(boolean isRcv) {
        Map<String, String> pendingBranches = this.branches(isRcv, false);
        Map<String, String> activeBranches = this.branches(isRcv, true);

        this.setActiveRecoveryGlobal(isRcv, this.hasRecoveryGlobal(isRcv));
        this.setPendingRecoveryGlobal(isRcv, false);
        activeBranches.clear();
        activeBranches.putAll(pendingBranches);
        pendingBranches.clear();

        boolean hasActiveRecovery = this.activeRecoveryGlobal(isRcv) || !activeBranches.isEmpty();
        if (!hasActiveRecovery) {
            return 0;
        }
        return isRcv ? RECOVERY_RCV : RECOVERY_SND;
    }

    private void clearActiveDirectionRecovery(boolean isRcv) {
        this.setActiveRecoveryGlobal(isRcv, false);
        if (isRcv) {
            if (this.activeRcvRecoveryBranches != null) {
                this.activeRcvRecoveryBranches.clear();
            }
        } else {
            if (this.activeSndRecoveryBranches != null) {
                this.activeSndRecoveryBranches.clear();
            }
        }
    }

    private boolean hasRecoveryGlobal(boolean isRcv) {
        return isRcv ? this.pendingRcvRecoveryGlobal : this.pendingSndRecoveryGlobal;
    }

    private void setPendingRecoveryGlobal(boolean isRcv, boolean state) {
        if (isRcv) {
            this.pendingRcvRecoveryGlobal = state;
        } else {
            this.pendingSndRecoveryGlobal = state;
        }
    }

    private boolean activeRecoveryGlobal(boolean isRcv) {
        return isRcv ? this.activeRcvRecoveryGlobal : this.activeSndRecoveryGlobal;
    }

    private void setActiveRecoveryGlobal(boolean isRcv, boolean state) {
        if (isRcv) {
            this.activeRcvRecoveryGlobal = state;
        } else {
            this.activeSndRecoveryGlobal = state;
        }
    }

    private Map<String, String> branches(boolean isRcv, boolean active) {
        if (isRcv) {
            if (active) {
                if (this.activeRcvRecoveryBranches == null) {
                    this.activeRcvRecoveryBranches = new LinkedHashMap<>();
                }
                return this.activeRcvRecoveryBranches;
            } else {
                if (this.pendingRcvRecoveryBranches == null) {
                    this.pendingRcvRecoveryBranches = new LinkedHashMap<>();
                }
                return this.pendingRcvRecoveryBranches;
            }
        } else {
            if (active) {
                if (this.activeSndRecoveryBranches == null) {
                    this.activeSndRecoveryBranches = new LinkedHashMap<>();
                }
                return this.activeSndRecoveryBranches;
            } else {
                if (this.pendingSndRecoveryBranches == null) {
                    this.pendingSndRecoveryBranches = new LinkedHashMap<>();
                }
                return this.pendingSndRecoveryBranches;
            }
        }
    }
}