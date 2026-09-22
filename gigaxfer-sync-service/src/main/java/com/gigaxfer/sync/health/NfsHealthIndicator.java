package com.gigaxfer.sync.health;

import com.gigaxfer.core.nfs.NfsException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.sync.SyncProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** nfs：經有界執行器 stat mount root；Busy / Timeout / IOException 皆 DOWN，原因帶 op（D34 修、D51）。 */
@Component("nfs")
public class NfsHealthIndicator implements HealthIndicator {
    private final NfsExecutor nfs;
    private final SyncProperties props;

    public NfsHealthIndicator(NfsExecutor nfs, SyncProperties props) {
        this.nfs = nfs;
        this.props = props;
    }

    @Override
    public Health health() {
        try {
            BasicFileAttributes attrs = nfs.call("stat-root",
                () -> Files.readAttributes(props.nfsRoot(), BasicFileAttributes.class));
            if (!attrs.isDirectory()) {
                return Health.down().withDetail("reason", "nfs root is not a directory").build();
            }
            return Health.up().withDetail("root", props.nfsRoot().toString()).build();
        } catch (NfsException e) {
            return Health.down().withDetail("op", e.op()).withDetail("reason", e.getClass().getSimpleName()).build();
        } catch (IOException e) {
            return Health.down(e).build();
        }
    }
}
