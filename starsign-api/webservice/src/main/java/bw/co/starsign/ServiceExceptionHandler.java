package bw.co.starsign;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Maps the services' exceptions to problem details, as knowvera's API advices do: a rule the caller broke
 * (thrown without a cause) is a 400, or a 404 when it says the record does not exist ({@code notFound});
 * an exception with a cause is an unexpected failure, a 500 whose details stay in the log.
 * <p>
 * Runs before the generated *ControllerAdvice classes, which would otherwise answer every service
 * exception with a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServiceExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceExceptionHandler.class);
    private static final MediaType PROBLEM_JSON_MEDIA_TYPE = MediaType.parseMediaType("application/problem+json");
    private static final String FALLBACK_MESSAGE = "Unknown error has occurred. Please contact administrator.";

    @ExceptionHandler(bw.co.starsign.audit.AuditTrailServiceException.class)
    public ResponseEntity<ProblemDetail> handleAuditTrailServiceException(bw.co.starsign.audit.AuditTrailServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.branding.BrandingServiceException.class)
    public ResponseEntity<ProblemDetail> handleBrandingServiceException(bw.co.starsign.branding.BrandingServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.document.DocumentIntegrityServiceException.class)
    public ResponseEntity<ProblemDetail> handleDocumentIntegrityServiceException(bw.co.starsign.document.DocumentIntegrityServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.document.DocumentProcessingServiceException.class)
    public ResponseEntity<ProblemDetail> handleDocumentProcessingServiceException(bw.co.starsign.document.DocumentProcessingServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.document.DocumentServiceException.class)
    public ResponseEntity<ProblemDetail> handleDocumentServiceException(bw.co.starsign.document.DocumentServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.identity.KeycloakAdminServiceException.class)
    public ResponseEntity<ProblemDetail> handleKeycloakAdminServiceException(bw.co.starsign.identity.KeycloakAdminServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.identity.OrganisationUserServiceException.class)
    public ResponseEntity<ProblemDetail> handleOrganisationUserServiceException(bw.co.starsign.identity.OrganisationUserServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.identity.RoleServiceException.class)
    public ResponseEntity<ProblemDetail> handleRoleServiceException(bw.co.starsign.identity.RoleServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.identity.UserInvitationServiceException.class)
    public ResponseEntity<ProblemDetail> handleUserInvitationServiceException(bw.co.starsign.identity.UserInvitationServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.messaging.OutboxServiceException.class)
    public ResponseEntity<ProblemDetail> handleOutboxServiceException(bw.co.starsign.messaging.OutboxServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.notification.NotificationProviderServiceException.class)
    public ResponseEntity<ProblemDetail> handleNotificationProviderServiceException(bw.co.starsign.notification.NotificationProviderServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.notification.NotificationServiceException.class)
    public ResponseEntity<ProblemDetail> handleNotificationServiceException(bw.co.starsign.notification.NotificationServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.notification.NotificationTemplateServiceException.class)
    public ResponseEntity<ProblemDetail> handleNotificationTemplateServiceException(bw.co.starsign.notification.NotificationTemplateServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.organisation.OrganisationServiceException.class)
    public ResponseEntity<ProblemDetail> handleOrganisationServiceException(bw.co.starsign.organisation.OrganisationServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.platform.PlatformConfigurationServiceException.class)
    public ResponseEntity<ProblemDetail> handlePlatformConfigurationServiceException(bw.co.starsign.platform.PlatformConfigurationServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.reporting.ReportingServiceException.class)
    public ResponseEntity<ProblemDetail> handleReportingServiceException(bw.co.starsign.reporting.ReportingServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.signing.SignatoryVerificationServiceException.class)
    public ResponseEntity<ProblemDetail> handleSignatoryVerificationServiceException(bw.co.starsign.signing.SignatoryVerificationServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.signing.SigningInvitationServiceException.class)
    public ResponseEntity<ProblemDetail> handleSigningInvitationServiceException(bw.co.starsign.signing.SigningInvitationServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.signing.SigningServiceException.class)
    public ResponseEntity<ProblemDetail> handleSigningServiceException(bw.co.starsign.signing.SigningServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.storage.StorageReconciliationServiceException.class)
    public ResponseEntity<ProblemDetail> handleStorageReconciliationServiceException(bw.co.starsign.storage.StorageReconciliationServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.storage.StorageServiceException.class)
    public ResponseEntity<ProblemDetail> handleStorageServiceException(bw.co.starsign.storage.StorageServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.subscription.SubscriptionServiceException.class)
    public ResponseEntity<ProblemDetail> handleSubscriptionServiceException(bw.co.starsign.subscription.SubscriptionServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.template.SigningTemplateServiceException.class)
    public ResponseEntity<ProblemDetail> handleSigningTemplateServiceException(bw.co.starsign.template.SigningTemplateServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.workflow.SigningWorkflowServiceException.class)
    public ResponseEntity<ProblemDetail> handleSigningWorkflowServiceException(bw.co.starsign.workflow.SigningWorkflowServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    @ExceptionHandler(bw.co.starsign.workflow.WorkflowEngineServiceException.class)
    public ResponseEntity<ProblemDetail> handleWorkflowEngineServiceException(bw.co.starsign.workflow.WorkflowEngineServiceException ex, WebRequest request) {
        return serviceProblem(ex, ex.isNotFound(), request);
    }

    /**
     * Tenant and ownership checks in the controllers (e.g. a caller without an organisation listing documents)
     * deny with Spring Security's exception; without this handler the fallback handler answers it with a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        LOGGER.warn("Access denied: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", ex.getMessage(), ex.getMessage(), request);
    }

    private ResponseEntity<ProblemDetail> serviceProblem(RuntimeException ex, boolean notFound, WebRequest request) {
        if (ex.getCause() != null) {
            LOGGER.error("Service error", ex);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "service-error", "Service Error",
                    FALLBACK_MESSAGE, null, request);
        }
        LOGGER.warn("Request rejected: {}", ex.getMessage());
        return notFound
                ? problem(HttpStatus.NOT_FOUND, "not-found", "Not Found", ex.getMessage(), ex.getMessage(), request)
                : problem(HttpStatus.BAD_REQUEST, "invalid-request", "Bad Request", ex.getMessage(), ex.getMessage(), request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String title, String detail,
            String error, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(URI.create(type));
        problemDetail.setTitle(title);
        problemDetail.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("errors", error == null || error.isBlank() ? List.of() : List.of(error));
        return ResponseEntity.status(status).contentType(PROBLEM_JSON_MEDIA_TYPE).body(problemDetail);
    }
}
