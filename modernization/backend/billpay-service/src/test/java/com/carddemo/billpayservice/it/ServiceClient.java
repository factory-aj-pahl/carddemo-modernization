package com.carddemo.billpayservice.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A browser-like HTTP client of the running standalone service: its own cookie jar (JSESSIONID and
 * XSRF-TOKEN), and the CSRF token sent back as {@code X-XSRF-TOKEN} on every non-GET request, as the web
 * app does.
 */
final class ServiceClient {

    static final String DEMO_PASSWORD = "PASSWORD";

    record Response(int status, String body) {
    }

    private final URI base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    ServiceClient(String baseUrl) {
        this.base = URI.create(baseUrl);
        this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        // The first GET issues the XSRF-TOKEN cookie, so even an anonymous POST passes the CSRF check.
        get("/api/screens/COSGN00C");
    }

    static String baseUrl() {
        String url = System.getProperty("billpay.it.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("billpay.it.url is not set: run through 'mvn verify' (failsafe starts the service)");
        }
        return url;
    }

    /** Signs on through the service's own sign-on screen; returns the sign-on screen response. */
    JsonNode signOn(String userId) {
        return body(post("/api/screens/COSGN00C", Map.of("key", "ENTER", "userId", userId, "password", DEMO_PASSWORD)));
    }

    /** One screen turn: first entry (GET) as the web app does before keys are accepted, then the POST. */
    JsonNode turn(String program, Map<String, ?> fields) {
        get("/api/screens/" + program);
        Map<String, Object> req = new LinkedHashMap<>(fields);
        req.putIfAbsent("key", "ENTER");
        Response r = post("/api/screens/" + program, req);
        if (r.status() != 200) {
            throw new AssertionError("POST " + program + " answered " + r.status() + ": " + r.body());
        }
        return body(r);
    }

    Response get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    Response post(String path, Object body) {
        try {
            return send(HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    Response send(String method, String path, Object body) {
        return switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path, body == null ? Map.of() : body);
            default -> throw new IllegalArgumentException("no request builder for " + method);
        };
    }

    JsonNode body(Response r) {
        try {
            return json.readTree(r.body());
        } catch (IOException e) {
            throw new IllegalStateException("not JSON (" + r.status() + "): " + r.body(), e);
        }
    }

    private Response send(HttpRequest.Builder req) {
        String csrf = cookies.getCookieStore().get(base).stream()
            .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElse(null);
        if (csrf != null) {
            req.header("X-XSRF-TOKEN", csrf);
        }
        try {
            HttpResponse<String> r = http.send(req.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException("standalone service at " + base + " did not answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
