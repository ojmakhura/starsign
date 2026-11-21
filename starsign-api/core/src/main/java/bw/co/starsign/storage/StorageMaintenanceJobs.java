package bw.co.starsign.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Housekeeping that has no caller: deleting objects whose scheduled deletion time has passed, and
 * completing uploads whose object reached the bucket although the reference was not updated. Failures are
 * logged and retried on the next run. Reconciliation itself is started by platform administrators.
 */
@Configuration
@EnableScheduling
public class StorageMaintenanceJobs
{
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageMaintenanceJobs.class);

    private final StorageService storageService;
    private final StorageReconciliationService storageReconciliationService;

    public StorageMaintenanceJobs(StorageService storageService, StorageReconciliationService storageReconciliationService)
    {
        this.storageService = storageService;
        this.storageReconciliationService = storageReconciliationService;
    }

    @Scheduled(cron = "${app.storage.purge-cron:0 15 * * * *}")
    public void purgeScheduledDeletions()
    {
        try
        {
            storageService.purgeScheduledDeletions();
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Purging scheduled storage deletions failed", e);
        }
    }

    @Scheduled(cron = "${app.storage.retry-cron:0 45 * * * *}")
    public void retryFailedUploads()
    {
        try
        {
            storageReconciliationService.retryFailedUploads();
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Retrying failed storage uploads failed", e);
        }
    }
}
