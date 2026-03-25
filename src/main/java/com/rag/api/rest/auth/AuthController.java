package com.rag.api.rest.auth;

import com.rag.domain.model.Permission;
import com.rag.domain.model.Role;
import com.rag.domain.model.User;
import com.rag.domain.repository.PermissionRepository;
import com.rag.domain.repository.RoleRepository;
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
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    public AuthController(UserRepository userRepository, RoleRepository roleRepository,
                          PermissionRepository permissionRepository, PasswordEncoder passwordEncoder,
                          JwtUtils jwtUtils) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
    }

    public record RegisterRequest(String username, String password, String email) {}
    public record LoginRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}

    public record UserDto(String id, String username, String email, String role) {}
    public record AuthResponse(String token, String refreshToken, UserDto user, long expiresIn) {}
    public record ErrorResponse(String error, String message) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("USERNAME_EXISTS", "Username already exists"));
        }
        if (request.email() != null && !request.email().isEmpty() && userRepository.existsByEmail(request.email())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("EMAIL_EXISTS", "Email already exists"));
        }

        Optional<Role> userRoleOpt = roleRepository.findByName(Role.USER);
        if (userRoleOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("ROLE_NOT_FOUND", "Default USER role not found"));
        }

        User user = new User(request.username(), passwordEncoder.encode(request.password()), request.email());
        user.setRole(userRoleOpt.get());

        User savedUser = userRepository.save(user);

        String token = jwtUtils.generateToken(savedUser);
        String refreshToken = jwtUtils.generateRefreshToken(savedUser);

        UserDto userDto = new UserDto(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail(),
                savedUser.getRole().getName()
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
        if (!user.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("ACCOUNT_DISABLED", "Account is disabled"));
        }

        String token = jwtUtils.generateToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user);

        UserDto userDto = new UserDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole() != null ? user.getRole().getName() : "USER"
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
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole() != null ? user.getRole().getName() : "USER"
        );

        return ResponseEntity.ok(new AuthResponse(newToken, newRefreshToken, userDto, jwtUtils.getExpiration()));
    }
}
