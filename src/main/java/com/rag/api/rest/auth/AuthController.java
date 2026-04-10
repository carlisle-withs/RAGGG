package com.rag.api.rest.auth;

import com.rag.domain.model.User;
import com.rag.domain.repository.UserRepository;
import com.rag.infrastructure.security.JwtUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtils jwtUtils) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
    }

    public record RegisterRequest(String username, String password) {}
    public record LoginRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}

    public record UserDto(String id, String username, String role) {}
    public record AuthResponse(String token, String refreshToken, UserDto user, long expiresIn) {}
    public record ErrorResponse(String error, String message) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("USERNAME_EXISTS", "Username already exists"));
        }

        User user = new User(request.username(), passwordEncoder.encode(request.password()), User.ROLE_USER);

        User savedUser = userRepository.save(user);

        String token = jwtUtils.generateToken(savedUser);
        String refreshToken = jwtUtils.generateRefreshToken(savedUser);

        UserDto userDto = new UserDto(
                savedUser.getId().toString(),
                savedUser.getUsername(),
                savedUser.getRole()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(token, refreshToken, userDto, jwtUtils.getExpiration()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<User> userOpt = userRepository.findByUsername(request.username());

        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("INVALID_CREDENTIALS", "Invalid username or password"));
        }

        User user = userOpt.get();
        if (user.getDeleted() != null && user.getDeleted()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("ACCOUNT_DISABLED", "Account is disabled"));
        }

        String token = jwtUtils.generateToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user);

        UserDto userDto = new UserDto(
                user.getId().toString(),
                user.getUsername(),
                user.getRole() != null ? user.getRole() : "USER"
        );

        return ResponseEntity.ok(new AuthResponse(token, refreshToken, userDto, jwtUtils.getExpiration()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
        String refreshToken = request.refreshToken();

        if (!jwtUtils.validateToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("INVALID_TOKEN", "Invalid or expired refresh token"));
        }

        String username = jwtUtils.extractUsername(refreshToken);
        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("USER_NOT_FOUND", "User not found"));
        }

        User user = userOpt.get();
        String newToken = jwtUtils.generateToken(user);
        String newRefreshToken = jwtUtils.generateRefreshToken(user);

        UserDto userDto = new UserDto(
                user.getId().toString(),
                user.getUsername(),
                user.getRole() != null ? user.getRole() : "USER"
        );

        return ResponseEntity.ok(new AuthResponse(newToken, newRefreshToken, userDto, jwtUtils.getExpiration()));
    }
}
