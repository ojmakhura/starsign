package bw.co.starsign.messaging;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OutboxRelayTest
{
    private final OutboxService outboxService = mock(OutboxService.class);
    private final OutboxRelay relay = new OutboxRelay(outboxService);

    @AfterEach
    void stop() throws InterruptedException
    {
        relay.shutdown();
    }

    @Test
    void anEnqueuedEventWakesTheRelay()
    {
        relay.onEnqueued(new OutboxEventEnqueued("e1"));

        verify(outboxService, timeout(2000)).publishPending(100);
    }

    @Test
    void wakeUpsWhileARunIsQueuedAreMergedIntoIt() throws InterruptedException
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        when(outboxService.publishPending(anyInt())).thenAnswer(invocation -> {
            running.countDown();
            release.await(2, TimeUnit.SECONDS);
            return 0;
        });

        relay.wake();
        running.await(2, TimeUnit.SECONDS);
        // The first run is busy: these queue exactly one more run between them
        for (int i = 0; i < 50; i++)
        {
            relay.wake();
        }
        release.countDown();

        verify(outboxService, timeout(2000).times(2)).publishPending(100);
        Thread.sleep(200);
        verify(outboxService, atMost(2)).publishPending(100);
    }

    @Test
    void aFullBatchDrainsAgainWithoutWaiting()
    {
        when(outboxService.publishPending(anyInt())).thenReturn(100, 100, 3);

        relay.wake();

        verify(outboxService, timeout(2000).times(3)).publishPending(100);
    }

    @Test
    void aFailedRunDoesNotStopLaterOnes()
    {
        when(outboxService.publishPending(anyInt())).thenThrow(new IllegalStateException("db down")).thenReturn(0);

        relay.wake();
        verify(outboxService, timeout(2000).times(1)).publishPending(100);
        relay.poll();

        verify(outboxService, timeout(2000).times(2)).publishPending(100);
    }
}
