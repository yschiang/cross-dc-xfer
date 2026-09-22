package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;

/** SR-04 三態。PendingConfirmation 表示結果未知，Application 重呼 finalize() 查證。 */
public sealed interface FinalizeResult {
    record Success(FileIdentity identity, String contentPath) implements FinalizeResult {}

    record Failure(FailureReason reason, String detail) implements FinalizeResult {}

    record PendingConfirmation(String op, String detail) implements FinalizeResult {}
}
