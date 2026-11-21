// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::notification::SmsGateway
 * STEREOTYPE:  Service
 */
package bw.co.starsign.notification;

import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * @see bw.co.starsign.notification.SmsGateway
 */
@Service("smsGateway")
public class SmsGatewayImpl
    extends SmsGatewayBase
{
    private final NovuNotifier novuNotifier;

    public SmsGatewayImpl(
        NovuNotifier novuNotifier) {

        super(
            );
        this.novuNotifier = novuNotifier;
    }

    /**
     * @see bw.co.starsign.notification.SmsGateway#send(String, String, String)
     */
    @Override
    protected String handleSend(String to, String message, String senderId)
        throws Exception
    {
        // The signature has no event type: the workflow comes from the GatewayCall set by the caller
        GatewayCall.Hint hint = GatewayCall.current();
        String workflowId = hint == null ? novuNotifier.workflowId("sms") : hint.workflowId();

        // The Novu workflow's SMS step uses {{payload.body}}; the sender id is configured on the Novu integration
        Map<String, Object> payload = new HashMap<String, Object>();
        if (hint != null && hint.payload() != null)
        {
            payload.putAll(hint.payload());
        }
        payload.put("body", message);
        if (StringUtils.isNotBlank(senderId))
        {
            payload.put("senderId", senderId);
        }

        String phone = to.trim();
        return novuNotifier.trigger(workflowId, phone, null, phone, hint == null ? null : hint.recipientName(),
                payload, hint == null ? null : hint.transactionId());
    }

}