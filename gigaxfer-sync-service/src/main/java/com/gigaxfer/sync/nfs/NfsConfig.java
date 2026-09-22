package com.gigaxfer.sync.nfs;

import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.sync.SyncProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 全 app 唯一的有界 NFS 執行器（D51）：固定槽、無佇列、timeout 不釋放槽。後續掃描、傳輸、自查都用它。 */
@Configuration
public class NfsConfig {
    @Bean(destroyMethod = "close")
    NfsExecutor nfsExecutor(SyncProperties props) {
        if (props.nfsRoot() == null) {
            throw new IllegalStateException("gigaxfer.nfs-root is not set");
        }
        return new BoundedNfsExecutor("nfs", props.nfsSlots(), props.nfsTimeout());
    }
}
