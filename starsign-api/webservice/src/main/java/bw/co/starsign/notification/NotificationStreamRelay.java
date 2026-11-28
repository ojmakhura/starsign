package bw.co.starsign.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Relays each signed-in user's Novu inbox socket to their portal tabs as Server-Sent Events. One socket.io
 * connection per user with the portal open, opened as that user (NovuInbox session token) and closed when their
 * last tab goes. Events: {@code unread} ({"count": n}) on connecting and whenever Novu's count changes, and
 * {@code notification} (a NotificationDTO) for each new in-app message.
 */
@Component
public class NotificationStreamRelay implements DisposableBean
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationStreamRelay.class);

    /** Streams end after this and the portal reconnects (with a fresh access token). */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private static final Duration KEEPALIVE = Duration.ofSeconds(25);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY = Duration.ofMinutes(1);

    private final NovuInbox inbox;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "novu-inbox-relay");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationStreamRelay(NovuInbox inbox)
    {
        this.inbox = inbox;
        scheduler.scheduleAtFixedRate(this::keepAlive, KEEPALIVE.toSeconds(), KEEPALIVE.toSeconds(), TimeUnit.SECONDS);
    }

    /** Whether live updates can be relayed: in-app notifications are in Novu and its socket address is known. */
    public boolean isEnabled()
    {
        return inbox.isEnabled() && inbox.socketUrl() != null;
    }

    /** A new stream for one portal tab of the subscriber. */
    public SseEmitter open(String subscriberId)
    {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        Channel channel = channels.compute(subscriberId, (id, existing) -> {
            Channel open = existing == null ? new Channel(id) : existing;
            open.emitters.add(emitter);
            return open;
        });
        // Outside compute: opening the session calls Novu
        channel.connect();
        emitter.onCompletion(() -> close(subscriberId, emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> close(subscriberId, emitter));
        try
        {
            send(emitter, "unread", Map.of("count", inbox.unreadCount(subscriberId)));
        }
        catch (RuntimeException e)
        {
            // The live events still come; the portal also asks for the count itself
            LOGGER.warn("Could not read the unread count for the notification stream: {}", e.toString());
        }
        return emitter;
    }

    private void close(String subscriberId, SseEmitter emitter)
    {
        channels.computeIfPresent(subscriberId, (id, channel) -> {
            channel.emitters.remove(emitter);
            if (channel.emitters.isEmpty())
            {
                channel.close();
                return null;
            }
            return channel;
        });
    }

    private void keepAlive()
    {
        for (Channel channel : channels.values())
        {
            for (SseEmitter emitter : channel.emitters)
            {
                try
                {
                    synchronized (emitter)
                    {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    }
                }
                catch (IOException | IllegalStateException e)
                {
                    emitter.completeWithError(e);
                }
            }
        }
    }

    private static void send(SseEmitter emitter, String event, Object data)
    {
        try
        {
            synchronized (emitter)
            {
                emitter.send(SseEmitter.event().name(event).data(data));
            }
        }
        catch (IOException | IllegalStateException e)
        {
            // The tab went away; onError/onCompletion removes it
            emitter.completeWithError(e);
        }
    }

    @Override
    public void destroy()
    {
        scheduler.shutdownNow();
        channels.values().forEach(Channel::close);
        channels.clear();
    }

    /** One user's Novu socket and the portal tabs it feeds. */
    private final class Channel
    {
        private final String subscriberId;
        private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<SseEmitter>();
        private Socket socket;
        private ScheduledFuture<?> retry;
        private Duration nextRetry = FIRST_RETRY;
        private boolean closed;

        Channel(String subscriberId)
        {
            this.subscriberId = subscriberId;
        }

        synchronized void connect()
        {
            if (closed || socket != null)
            {
                return;
            }
            try
            {
                socket = IO.socket(address(), options(inbox.token(subscriberId)));
            }
            catch (RuntimeException e)
            {
                LOGGER.warn("Could not open the Novu inbox socket: {}", e.toString());
                retryLater(false);
                return;
            }
            Socket current = socket;
            current.on(Socket.EVENT_CONNECT, args -> connected());
            current.on(Socket.EVENT_CONNECT_ERROR, args -> {
                LOGGER.debug("Novu inbox socket refused: {}", args.length > 0 ? args[0] : "");
                dropped(current, true);
            });
            current.on(Socket.EVENT_DISCONNECT, args -> dropped(current, false));
            current.on("notification_received", args -> received(args));
            current.on("unread_count_changed", args -> unreadChanged(args));
            current.connect();
        }

        private synchronized void connected()
        {
            nextRetry = FIRST_RETRY;
            LOGGER.debug("Novu inbox socket connected for {} portal tab(s)", emitters.size());
        }

        /** Our own reconnection: a refused session gets a new token, which socket.io's would not. */
        private synchronized void dropped(Socket which, boolean refused)
        {
            if (closed || which != socket)
            {
                return;
            }
            socket.off();
            socket.close();
            socket = null;
            retryLater(refused);
        }

        private void retryLater(boolean renewSession)
        {
            if (renewSession)
            {
                inbox.forget(subscriberId);
            }
            if (retry != null && !retry.isDone())
            {
                return;
            }
            long delay = nextRetry.toMillis();
            nextRetry = nextRetry.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : nextRetry.multipliedBy(2);
            retry = scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }

        synchronized void close()
        {
            closed = true;
            if (retry != null)
            {
                retry.cancel(false);
            }
            if (socket != null)
            {
                socket.off();
                socket.close();
                socket = null;
            }
        }

        private void received(Object[] args)
        {
            JsonNode body = parse(args);
            JsonNode message = body == null ? null : body.path("message");
            if (message == null || message.isMissingNode())
            {
                return;
            }
            NotificationDTO notification = inbox.toDto(message);
            emitters.forEach(emitter -> send(emitter, "notification", notification));
        }

        private void unreadChanged(Object[] args)
        {
            JsonNode body = parse(args);
            if (body == null)
            {
                return;
            }
            // Novu 3 sends {"counts": {"total": n, ...}}; older versions {"unreadCount": n}
            JsonNode count = body.path("counts").path("total");
            if (!count.isNumber())
            {
                count = body.path("unreadCount");
                count = count.isObject() ? count.path("total") : count;
            }
            if (count.isNumber())
            {
                Map<String, Integer> data = Map.of("count", count.asInt());
                emitters.forEach(emitter -> send(emitter, "unread", data));
            }
        }

        private JsonNode parse(Object[] args)
        {
            if (args.length == 0 || args[0] == null)
            {
                return null;
            }
            try
            {
                return json.readTree(args[0].toString());
            }
            catch (IOException e)
            {
                LOGGER.debug("Ignoring an unreadable Novu socket event: {}", e.toString());
                return null;
            }
        }
    }

    /** The socket.io origin; a path (Novu behind a proxy, e.g. https://host/novu/ws) is socket.io's path under it. */
    private URI address()
    {
        URI uri = URI.create(inbox.socketUrl());
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    private IO.Options options(String token)
    {
        String prefix = StringUtils.removeEnd(StringUtils.defaultString(URI.create(inbox.socketUrl()).getRawPath()), "/");
        return IO.Options.builder()
                .setTransports(new String[] { WebSocket.NAME })
                .setPath(prefix + "/socket.io/")
                .setQuery("token=" + token)
                .setReconnection(false)
                .build();
    }
}
