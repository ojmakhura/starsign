package bw.co.starsign.notification;

import bw.co.starsign.context.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live in-app notifications for the portal, relayed from the user's Novu inbox socket (NotificationStreamRelay).
 * Hand-written, not part of the generated NotificationController: the portal reads it with fetch, as an
 * EventSource cannot send the bearer token.
 */
@RestController
@RequestMapping("/notifications")
@Tag(name = "Notifications", description = "In-app notifications.")
public class NotificationStream
{
    private final NotificationStreamRelay relay;
    private final CallerContext caller;

    public NotificationStream(NotificationStreamRelay relay, CallerContext caller)
    {
        this.relay = relay;
        this.caller = caller;
    }

    /** 204 when there is nothing to relay (no Novu inbox or socket address): the portal polls the count then. */
    @GetMapping(value = "/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Notification stream",
            description = "Server-Sent Events: 'unread' ({count}) and 'notification' (NotificationDTO)")
    public ResponseEntity<SseEmitter> stream()
    {
        if (!relay.isEnabled())
        {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                // nginx and similar proxies would otherwise hold the events back
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(relay.open(caller.userId()));
    }
}
