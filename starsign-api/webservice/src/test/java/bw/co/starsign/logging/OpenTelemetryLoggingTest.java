package bw.co.starsign.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The logs reach OpenTelemetry: logback-spring.xml's OTEL appender, once {@link InstallOpenTelemetryAppender}
 * connects it to the SDK, hands every event to the SDK's log exporter, with its level, the logging code, the MDC
 * and the trace of the request that logged it.
 */
class OpenTelemetryLoggingTest
{
    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private OpenTelemetrySdk openTelemetry;

    @BeforeEach
    void setUp() throws Exception
    {
        // The application's own Logback configuration, as Spring Boot loads it
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setLoggerProvider(SdkLoggerProvider.builder()
                        .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                        .build())
                .build();
        new InstallOpenTelemetryAppender(openTelemetry).afterPropertiesSet();
    }

    @AfterEach
    void tearDown()
    {
        MDC.clear();
        openTelemetry.close();
    }

    @Test
    void logEventsAreExportedWithTheirTraceLevelCodeAndMdc()
    {
        Span span = openTelemetry.getTracer("test").spanBuilder("sign").startSpan();
        try (Scope scope = span.makeCurrent())
        {
            MDC.put("workflowId", "WF-42");
            LoggerFactory.getLogger(OpenTelemetryLoggingTest.class).warn("Signatory {} declined", "ada@acme.test");
        }
        finally
        {
            span.end();
        }

        List<LogRecordData> records = exporter.getFinishedLogRecordItems();
        assertThat(records).hasSize(1);
        LogRecordData record = records.get(0);
        assertThat(record.getBodyValue().asString()).isEqualTo("Signatory ada@acme.test declined");
        assertThat(record.getSeverity()).isEqualTo(Severity.WARN);
        assertThat(record.getInstrumentationScopeInfo().getName()).isEqualTo(OpenTelemetryLoggingTest.class.getName());
        assertThat(record.getSpanContext().getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(record.getSpanContext().getSpanId()).isEqualTo(span.getSpanContext().getSpanId());
        assertThat(record.getAttributes().get(AttributeKey.stringKey("workflowId"))).isEqualTo("WF-42");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("code.namespace")))
                .isEqualTo(OpenTelemetryLoggingTest.class.getName());
        assertThat(record.getAttributes().get(AttributeKey.stringKey("code.function")))
                .isEqualTo("logEventsAreExportedWithTheirTraceLevelCodeAndMdc");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("thread.name"))).isNotBlank();
    }

    @Test
    void debugEventsBelowTheRootLevelAreNotExported()
    {
        LoggerFactory.getLogger(OpenTelemetryLoggingTest.class).debug("Not sent");

        assertThat(exporter.getFinishedLogRecordItems()).isEmpty();
    }
}
