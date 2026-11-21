package bw.co.starsign.notification;

import co.novu.Novu;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Novu client used by the email and SMS gateways, and scheduling for the outbox relay, the
 * notification retries and the signing reminders.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(NovuProperties.class)
public class NotificationConfiguration
{
    /**
     * Building the client does not contact Novu, so it is built even when delivery is disabled;
     * {@link NovuNotifier} does not call it then.
     */
    @Bean
    public Novu novuSdk(NovuProperties properties)
    {
        return Novu.builder()
                .serverURL(StringUtils.defaultIfBlank(properties.getBaseUrl(), "https://api.novu.co"))
                .secretKey(StringUtils.defaultString(properties.getSecretKey()))
                .build();
    }
}
