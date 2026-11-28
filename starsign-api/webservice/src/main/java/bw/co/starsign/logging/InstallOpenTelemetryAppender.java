package bw.co.starsign.logging;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Connects the {@code OTEL} appender of logback-spring.xml to the OpenTelemetry SDK that Spring Boot configures,
 * so log events are exported over OTLP (management.opentelemetry.logging.export.otlp) together with the traces
 * and metrics. Logback starts before the application context, so the appender only gets the SDK here; events
 * logged before that are dropped by it (the console still has them).
 */
@Component
public class InstallOpenTelemetryAppender implements InitializingBean
{
    private final OpenTelemetry openTelemetry;

    InstallOpenTelemetryAppender(OpenTelemetry openTelemetry)
    {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet()
    {
        OpenTelemetryAppender.install(this.openTelemetry);
    }
}
