package bw.co.starsign.config;

import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * Services call each other inside one transaction (the generated *ServiceBase classes are transactional), and some
 * catch a failure of another service on purpose, e.g. reading the Keycloak account for /users/me and falling back
 * to the token. By default Spring marks the whole transaction rollback-only as soon as a participating call
 * throws, so the outer commit then fails although the caller handled the error. Here only the outermost
 * transaction decides: an exception that escapes it still rolls everything back.
 */
@Configuration
public class TransactionConfig
{
    @Bean
    public TransactionManagerCustomizer<AbstractPlatformTransactionManager> participationFailureCustomizer()
    {
        return transactionManager -> transactionManager.setGlobalRollbackOnParticipationFailure(false);
    }
}
