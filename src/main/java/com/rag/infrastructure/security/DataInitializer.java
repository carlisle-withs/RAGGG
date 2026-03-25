package com.rag.infrastructure.security;

import com.rag.domain.model.Permission;
import com.rag.domain.model.Role;
import com.rag.domain.model.User;
import com.rag.domain.repository.PermissionRepository;
import com.rag.domain.repository.RoleRepository;
import com.rag.domain.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Component
public class DataInitializer implements CommandLineRunner {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(PermissionRepository permissionRepository, RoleRepository roleRepository,
                          UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        initializePermissions();
        initializeRoles();
        initializeDefaultUsers();
    }

    private void initializePermissions() {
        createPermissionIfNotExists(Permission.READ_KB, "Read knowledge bases");
        createPermissionIfNotExists(Permission.WRITE_KB, "Create and update knowledge bases");
        createPermissionIfNotExists(Permission.DELETE_KB, "Delete knowledge bases");
        createPermissionIfNotExists(Permission.READ_DOCUMENT, "Read documents");
        createPermissionIfNotExists(Permission.WRITE_DOCUMENT, "Upload and update documents");
        createPermissionIfNotExists(Permission.DELETE_DOCUMENT, "Delete documents");
        createPermissionIfNotExists(Permission.MANAGE_USERS, "Manage users");
    }

    private void createPermissionIfNotExists(String name, String description) {
        if (permissionRepository.findByName(name).isEmpty()) {
            permissionRepository.save(new Permission(name, description));
        }
    }

    private void initializeRoles() {
        createAdminRoleIfNotExists();
        createUserRoleIfNotExists();
    }

    private void createAdminRoleIfNotExists() {
        if (roleRepository.findByName(Role.ADMIN).isEmpty()) {
            Role adminRole = new Role(Role.ADMIN, "Administrator with all permissions");
            adminRole.setPermissions(Set.copyOf(permissionRepository.findAll()));
            roleRepository.save(adminRole);
        }
    }

    private void createUserRoleIfNotExists() {
        if (roleRepository.findByName(Role.USER).isEmpty()) {
            Role userRole = new Role(Role.USER, "Regular user with basic permissions");
            permissionRepository.findByName(Permission.READ_KB).ifPresent(userRole::addPermission);
            permissionRepository.findByName(Permission.WRITE_KB).ifPresent(userRole::addPermission);
            permissionRepository.findByName(Permission.READ_DOCUMENT).ifPresent(userRole::addPermission);
            permissionRepository.findByName(Permission.WRITE_DOCUMENT).ifPresent(userRole::addPermission);
            roleRepository.save(userRole);
        }
    }

    private void initializeDefaultUsers() {
        if (userRepository.findByUsername("admin").isEmpty()) {
            Role adminRole = roleRepository.findByName(Role.ADMIN).orElse(null);
            User admin = new User("admin", passwordEncoder.encode("admin123"), "admin@rag.local");
            admin.setRole(adminRole);
            userRepository.save(admin);
        }
    }
}
