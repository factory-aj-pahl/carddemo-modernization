package com.carddemo.iam.security;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * D-AUTH: server-side sessions, roles from the session (C-ROLE), CSRF token in a cookie for the SPA.
 * Requests without a valid session get 401 and the web app shows a clean sign-on screen; a USER
 * calling an ADMIN endpoint gets 403 with the reused COMEN01C admin-only message.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /** C-ROLE: admin menu, user maintenance, and transaction-type maintenance screens. */
    static final String[] ADMIN_SCREENS = {
        "/api/screens/COADM01C", "/api/screens/COADM01C/**",
        "/api/screens/COUSR00C", "/api/screens/COUSR00C/**", "/api/screens/COUSR01C", "/api/screens/COUSR01C/**",
        "/api/screens/COUSR02C", "/api/screens/COUSR02C/**", "/api/screens/COUSR03C", "/api/screens/COUSR03C/**",
        "/api/screens/COTRTLIC", "/api/screens/COTRTLIC/**", "/api/screens/COTRTUPC", "/api/screens/COTRTUPC/**"};

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper json, Clock clock, SecurityProperties props) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/screens/COSGN00C", "/api/screens/COSGN00C/**", "/api/session").permitAll()
                .requestMatchers("/actuator/health", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(ADMIN_SCREENS).hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
            .sessionManagement(sm -> sm.sessionFixation(f -> f.newSession()))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(new IdleTimeoutFilter(clock, props.idleTimeout()), AuthorizationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((req, res, e) -> {
                    if (req.getRequestURI().startsWith("/api/") && !(e instanceof org.springframework.security.web.csrf.CsrfException)) {
                        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        json.writeValue(res.getOutputStream(),
                            ScreenResponse.navigate("COMEN01C", CommonMessages.ADMIN_ONLY, MessageColor.RED));
                    } else {
                        res.sendError(HttpServletResponse.SC_FORBIDDEN);
                    }
                }))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());
        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder(SecurityProperties props) {
        return new BCryptPasswordEncoder(props.bcryptCost());
    }

    /** Sign-on is handled by SignonService; this only stops Spring Boot from creating a default user. */
    @Bean
    public UserDetailsService noDefaultUser() {
        return username -> {
            throw new UsernameNotFoundException("not used");
        };
    }
}
