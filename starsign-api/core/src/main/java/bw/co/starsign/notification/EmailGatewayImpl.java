// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::notification::EmailGateway
 * STEREOTYPE:  Service
 */
package bw.co.starsign.notification;

import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * @see bw.co.starsign.notification.EmailGateway
 */
@Service("emailGateway")
public class EmailGatewayImpl
    extends EmailGatewayBase
{
    /** Header naming the Novu workflow (else the one of the current {@link GatewayCall}, else a generic one). */
    public static final String WORKFLOW_HEADER = "X-StarSign-Workflow";
    /** Header with the Novu transaction id, so a retried send is not delivered twice. */
    public static final String TRANSACTION_HEADER = "X-StarSign-Transaction";

    private final NovuNotifier novuNotifier;

    public EmailGatewayImpl(
        NovuNotifier novuNotifier) {

        super(
            );
        this.novuNotifier = novuNotifier;
    }

    /**
     * @see bw.co.starsign.notification.EmailGateway#send(String, String, String, String, String, Map)
     */
    @Override
    protected String handleSend(String from, String to, String subject, String htmlBody, String textBody, Map headers)
        throws Exception
    {
        GatewayCall.Hint hint = GatewayCall.current();
        String workflowId = StringUtils.firstNonBlank(header(headers, WORKFLOW_HEADER),
                hint == null ? null : hint.workflowId(), novuNotifier.workflowId("email"));
        String transactionId = StringUtils.firstNonBlank(header(headers, TRANSACTION_HEADER),
                hint == null ? null : hint.transactionId());

        // The Novu workflow's email step uses {{payload.subject}} and {{payload.body}}: the message is rendered here
        Map<String, Object> payload = new HashMap<String, Object>();
        if (hint != null && hint.payload() != null)
        {
            payload.putAll(hint.payload());
        }
        payload.put("from", from);
        payload.put("subject", subject);
        payload.put("body", htmlBody);
        payload.put("text", textBody);

        String email = to.trim();
        return novuNotifier.trigger(workflowId, email, email, null, hint == null ? null : hint.recipientName(),
                payload, transactionId);
    }

    private static String header(Map headers, String name)
    {
        Object value = headers.get(name);
        return value == null ? null : value.toString();
    }

}