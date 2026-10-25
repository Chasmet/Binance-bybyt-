package com.chk.binancebybit;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Android schedules this opportunistically when network is available.
 * Do not call startForegroundService from background, and do not keep
 * unrestricted wake locks. No trade operations are accessible.
 */
public final class CryptoUniversalJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        if (!CryptoUniversalAgent.paired(getApplicationContext())) return false;
        new Thread(() -> {
            try { CryptoUniversalAgent.runOnce(getApplicationContext()); }
            finally { jobFinished(params,false); }
        }, "CHK-MCP-Scheduled-Read").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        return CryptoUniversalAgent.paired(getApplicationContext());
    }
}
