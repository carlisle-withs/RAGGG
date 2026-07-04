package com.rag.api.rest.user;

import com.rag.domain.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    public record CurrentUser(
            Long id,
            String username,
            String role,
            String avatar,
            Boolean deleted
    ) {}

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return ResponseEntity.ok(Map.of(
                    "id", user.getId(),
                    "username", user.getUsername(),
                    "role", user.getRole(),
                    "avatar", user.getAvatar() != null ? user.getAvatar() : "",
                    "deleted", user.getDeleted() != null ? user.getDeleted() : false
            ));
        }
        return ResponseEntity.ok(Map.of(
                "id", 0L,
                "username", "guest",
                "role", "GUEST",
                "avatar", "",
                "deleted", false
        ));
    }
}
