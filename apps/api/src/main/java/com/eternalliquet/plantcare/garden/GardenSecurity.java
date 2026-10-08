package com.eternalliquet.plantcare.garden;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
class GardenSecurity {
  GardenSecurity(@Value("${server.address:127.0.0.1}") String address) {
    if (!java.util.Set.of("127.0.0.1", "::1", "localhost").contains(address))
      throw new IllegalStateException(
          "This private garden must bind to loopback. Use an HTTPS reverse proxy; do not expose the"
              + " app port directly.");
  }

  @Bean
  org.springframework.security.crypto.password.PasswordEncoder gardenPasswordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService gardenUsers(@Value("${app.password:}") String password) {
    if (!password.isBlank()
        && (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)) {
      throw new IllegalStateException(
          "GARDEN_PASSWORD must contain at least 12 characters and at most 72 UTF-8 bytes.");
    }
    return new InMemoryUserDetailsManager(
        User.withUsername("gardener")
            .password(
                new BCryptPasswordEncoder()
                    .encode(password.isBlank() ? UUID.randomUUID().toString() : password))
            .disabled(password.isBlank())
            .roles("GARDENER")
            .build());
  }

  @Bean
  SecurityFilterChain gardenSecurityFilter(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/plants/**")
                    .denyAll()
                    .requestMatchers("/api/garden/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .formLogin(
            f ->
                f.loginPage("/")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/?login=failed"))
        .logout(
            l ->
                l.logoutUrl("/logout")
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID"))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (r, s, x) -> {
                      s.setStatus(401);
                      s.setContentType("application/json");
                      s.getWriter().write("{\"message\":\"Please sign in again.\"}");
                    }))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; img-src 'self' blob: data:; object-src 'none';"
                                + " frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
        .addFilterBefore(new LoginLimit(), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  static UUID owner(Authentication auth) {
    return UUID.nameUUIDFromBytes(("garden:" + auth.getName()).getBytes(StandardCharsets.UTF_8));
  }

  /** Bounded single-garden login limit; internet operators still need edge abuse protection. */
  static class LoginLimit extends OncePerRequestFilter {
    private long window;
    private int attempts;

    private synchronized boolean allow() {
      long now = Instant.now().getEpochSecond();
      if (now - window >= 60) {
        window = now;
        attempts = 0;
      }
      return ++attempts <= 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
        throws ServletException, IOException {
      if (r.getMethod().equals("POST") && r.getServletPath().equals("/login") && !allow()) {
        s.setStatus(429);
        s.setHeader("Retry-After", "60");
        s.setContentType("text/plain");
        s.getWriter()
            .write("Too many sign-in attempts. Wait one minute, then go back and try again.");
        return;
      }
      chain.doFilter(r, s);
    }
  }
}

@RestController
class GardenSessionController {
  private final boolean configured;

  GardenSessionController(@Value("${app.password:}") String password) {
    configured = !password.isBlank();
  }

  @GetMapping("/api/session")
  Map<String, Object> session(Authentication auth, CsrfToken csrf) {
    return Map.of(
        "authenticated",
        auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken),
        "configured",
        configured,
        "csrfToken",
        csrf.getToken(),
        "csrfHeader",
        csrf.getHeaderName());
  }
}
