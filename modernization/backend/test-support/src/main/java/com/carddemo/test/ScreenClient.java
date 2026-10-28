package com.carddemo.test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Drives screen turns ({@code /api/screens/<PROGRAM>}) like the web app does: one browser session,
 * CSRF token attached, JSON in and out. The session follows sign-on (new session id) and sign-off.
 */
public final class ScreenClient {

    /** Demo password of the sample USRSEC users (README.md). */
    public static final String DEMO_PASSWORD = "PASSWORD";

    private final MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private MockHttpSession session = new MockHttpSession();

    public ScreenClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** Signs on and returns the sign-on response; the client keeps the new session. */
    public JsonNode signOn(String userId, String password) {
        return post("COSGN00C", "ENTER", Map.of("userId", userId, "password", password));
    }

    public JsonNode signOn(String userId) {
        return signOn(userId, DEMO_PASSWORD);
    }

    /** First entry of a program (GET); expects HTTP 200. */
    public JsonNode show(String program) {
        return body(expect(200, exchange(MockMvcRequestBuilders.get(path(program)))));
    }

    /** One screen turn (POST) with the key and fields; expects HTTP 200. */
    public JsonNode post(String program, String key, Map<String, ?> fields) {
        return body(expect(200, turn(program, key, fields)));
    }

    /** One screen turn without asserting the status. */
    public MockHttpServletResponse turn(String program, String key, Map<String, ?> fields) {
        Map<String, Object> req = new LinkedHashMap<>(fields);
        req.put("key", key);
        try {
            return exchange(MockMvcRequestBuilders.post(path(program)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** First entry without asserting the status. */
    public MockHttpServletResponse showRaw(String program) {
        return exchange(MockMvcRequestBuilders.get(path(program)));
    }

    public JsonNode body(MockHttpServletResponse res) {
        try {
            return json.readTree(res.getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public MockHttpSession session() {
        return session;
    }

    private MockHttpServletResponse exchange(MockHttpServletRequestBuilder req) {
        try {
            MvcResult r = mvc.perform(req.session(session)).andReturn();
            HttpSession s = r.getRequest().getSession(false);
            session = s instanceof MockHttpSession m && !m.isInvalid() ? m : new MockHttpSession();
            return r.getResponse();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockHttpServletResponse expect(int status, MockHttpServletResponse res) {
        if (res.getStatus() != status) {
            String content;
            try {
                content = res.getContentAsString();
            } catch (Exception e) {
                content = "?";
            }
            throw new AssertionError("Expected HTTP " + status + " but got " + res.getStatus() + ": " + content);
        }
        return res;
    }

    private static String path(String program) {
        return "/api/screens/" + program;
    }
}
