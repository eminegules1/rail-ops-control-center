package com.railops.backend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AuthenticationManager authentication;
    private final TokenService tokens;

    AuthController(AuthenticationManager authentication, TokenService tokens) {
        this.authentication = authentication;
        this.tokens = tokens;
    }

    @Operation(summary = "Sign in",
            description = "Returns a bearer token valid for 8 hours. Unknown users and wrong passwords get the same 401.")
    @SecurityRequirements
    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication user = authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        Role role = user.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(name -> name.startsWith(ROLE_PREFIX))
                .map(name -> Role.valueOf(name.substring(ROLE_PREFIX.length())))
                .findFirst()
                .orElseThrow();
        TokenService.IssuedToken token = tokens.issue(user.getName(), role);
        return new LoginResponse(token.value(), "Bearer", user.getName(), role, token.expiresAt());
    }
}
