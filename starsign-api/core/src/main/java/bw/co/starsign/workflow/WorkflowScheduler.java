package bw.co.starsign.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires workflows, stages and signatories past their deadlines, and re-drives workflows left between two
 * steps (or with a final document still to produce).
 */
@Component
@EnableScheduling
public class WorkflowScheduler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowEngineService workflowEngineService;

    public WorkflowScheduler(WorkflowEngineService workflowEngineService)
    {
        this.workflowEngineService = workflowEngineService;
    }

    @Scheduled(initialDelayString = "${app.workflows.initial-delay-ms:60000}",
            fixedDelayString = "${app.workflows.expiry-interval-ms:300000}")
    public void expireOverdue()
    {
        try
        {
            int expired = workflowEngineService.expireOverdue();
            if (expired > 0)
            {
                LOGGER.info("Expired {} workflows, signatories and invitations past their deadline", expired);
            }
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Expiring overdue workflows failed; it runs again at the next tick", e);
        }
    }

    @Scheduled(initialDelayString = "${app.workflows.initial-delay-ms:60000}",
            fixedDelayString = "${app.workflows.recovery-interval-ms:600000}")
    public void recoverStalled()
    {
        try
        {
            int recovered = workflowEngineService.recoverStalled();
            if (recovered > 0)
            {
                LOGGER.info("Moved on {} stalled workflows", recovered);
            }
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Recovering stalled workflows failed; it runs again at the next tick", e);
        }
    }
}
