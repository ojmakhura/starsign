package bw.co.starsign.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins allowed to call the API from a browser: the organisation portal, the platform administration
 * portal and the external signing portal.
 */
@ConfigurationProperties("app.cors")
public class CorsProperties {

    /** Origin patterns, e.g. {@code https://portal.starsign.example}; never {@code *} in production. */
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:4700"));

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
