package bw.co.starsign.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeliverableVerificationTest
{
    @Test
    void smsCodesGoByEmailWhileTextMessagesAreOff()
    {
        assertEquals(VerificationMethod.EMAIL_OTP, SigningDocuments.deliverable(VerificationMethod.SMS_OTP, false));
        assertEquals(VerificationMethod.SMS_OTP, SigningDocuments.deliverable(VerificationMethod.SMS_OTP, true));
    }

    @Test
    void otherMethodsAreUnchanged()
    {
        for (VerificationMethod method : VerificationMethod.values())
        {
            if (method != VerificationMethod.SMS_OTP)
            {
                assertEquals(method, SigningDocuments.deliverable(method, false));
            }
        }
    }
}
