package com.sinx.platform.bootstrap.security;

import java.util.ArrayList;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.sinx.platform.identity.security.IdentitySecurityProperties;
import com.sinx.platform.identity.security.RegistrationSecurityProperties;
import com.sinx.platform.notification.email.VerificationMailProperties;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({
    IdentitySecurityProperties.class,
    RegistrationSecurityProperties.class,
    VerificationMailProperties.class
})
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            ArrayList<GrantedAuthority> granted = new ArrayList<>(
                authorities.convert(jwt)
            );
            String scope = jwt.getClaimAsString("scope");
            if ("USER".equals(scope) || "ADMIN".equals(scope)) {
                granted.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
            return granted;
        });
        return converter;
    }

    @Bean
    SecurityFilterChain applicationSecurity(
        HttpSecurity http,
        JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/health",
                    "/gateway",
                    "/actuator/health",
                    "/session/register",
                    "/session/registration/config",
                    "/session/registration/email-code",
                    "/session/login",
                    "/session/refresh",
                    "/session/current",
                    "/session/password-reset/request",
                    "/session/password-reset/confirm",
                    "/admin-session/login",
                    "/admin-session/login/mfa",
                    "/admin-session/enrollment",
                    "/admin-session/enrollment/confirm",
                    "/admin-session/refresh",
                    "/admin-session/current"
                ).permitAll()
                .requestMatchers(
                    "/session/password",
                    "/session/invitations"
                ).access((authentication, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        authentication.get().getAuthorities().stream()
                            .anyMatch(authority ->
                                "ROLE_USER".equals(authority.getAuthority())
                            )
                            && authentication.get().getAuthorities().stream()
                                .anyMatch(authority ->
                                    "SCOPE_USER".equals(authority.getAuthority())
                                )
                    )
                )
                .requestMatchers(
                    "/admin-session/mfa",
                    "/admin-session/mfa/**"
                ).access((authentication, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        authentication.get().getAuthorities().stream()
                            .anyMatch(authority ->
                                "ROLE_ADMIN".equals(authority.getAuthority())
                            )
                            && authentication.get().getAuthorities().stream()
                                .anyMatch(authority ->
                                    "SCOPE_ADMIN".equals(authority.getAuthority())
                            )
                    )
                )
                .requestMatchers("/api/v2/admin/**")
                .access((authentication, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        authentication.get().getAuthorities().stream()
                            .anyMatch(authority ->
                                "ROLE_ADMIN".equals(authority.getAuthority())
                            )
                            && authentication.get().getAuthorities().stream()
                                .anyMatch(authority ->
                                    "SCOPE_ADMIN".equals(authority.getAuthority())
                                )
                    )
                )
                .requestMatchers("/control/catalog/**")
                .access((authentication, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        authentication.get().getAuthorities().stream()
                            .anyMatch(authority ->
                                "ROLE_ADMIN".equals(authority.getAuthority())
                            )
                            && authentication.get().getAuthorities().stream()
                                .anyMatch(authority ->
                                    "SCOPE_ADMIN".equals(authority.getAuthority())
                                )
                    )
                )
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            )
            .headers(Customizer.withDefaults())
            .build();
    }
}
