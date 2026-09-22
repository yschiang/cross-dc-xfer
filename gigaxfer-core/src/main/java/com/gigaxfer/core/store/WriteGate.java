package com.gigaxfer.core.store;

import java.util.Optional;

/** beginWrite 前的閘門：Policy 登錄（Q16/Q17）與容量（D21）由 library starter 實作；core 只定義契約。 */
@FunctionalInterface
public interface WriteGate {
    /** 空 = 放行；非空 = 拒絕原因。 */
    Optional<String> rejectReason(String namespace, String dataClass);

    static WriteGate open() {
        return (ns, cls) -> Optional.empty();
    }
}
