package com.carddemo.authorization.internal;

import com.carddemo.authorization.api.AuthorizationJobResult;
import com.carddemo.authorization.api.AuthorizationJobs;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
class AuthorizationJobService implements AuthorizationJobs {

    private final PurgeJob purge;
    private final UnloadJob unload;
    private final LoadJob load;

    AuthorizationJobService(PurgeJob purge, UnloadJob unload, LoadJob load) {
        this.purge = purge;
        this.unload = unload;
        this.load = load;
    }

    @Override
    public AuthorizationJobResult purgeExpired(String sysin) {
        return purge.run(sysin);
    }

    @Override
    public AuthorizationJobResult unload(Path rootFile, Path childFile) {
        return unload.run(UnloadJob.Program.PAUDBUNL, rootFile, childFile);
    }

    @Override
    public AuthorizationJobResult load(Path rootFile, Path childFile) {
        return load.run(rootFile, childFile);
    }

    @Override
    public AuthorizationJobResult unloadGsam(Path summaryFile, Path detailFile) {
        return unload.run(UnloadJob.Program.DBUNLDGS, summaryFile, detailFile);
    }
}
