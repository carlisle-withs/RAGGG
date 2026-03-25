package com.rag.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    public Permission() {
        this.id = UUID.randomUUID().toString();
    }

    public Permission(String name, String description) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public static final String READ_KB = "READ_KB";
    public static final String WRITE_KB = "WRITE_KB";
    public static final String DELETE_KB = "DELETE_KB";
    public static final String READ_DOCUMENT = "READ_DOCUMENT";
    public static final String WRITE_DOCUMENT = "WRITE_DOCUMENT";
    public static final String DELETE_DOCUMENT = "DELETE_DOCUMENT";
    public static final String MANAGE_USERS = "MANAGE_USERS";
}
