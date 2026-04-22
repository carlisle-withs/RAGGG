package com.rag.api.rest.user;

import org.springframework.http.ResponseEntity;
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
        // 未开启认证时，返回一个匿名 guest 用户
        return ResponseEntity.ok(Map.of(
                "id", 0L,
                "username", "guest",
                "role", "GUEST",
                "avatar", "",
                "deleted", false
        ));
    }
}
