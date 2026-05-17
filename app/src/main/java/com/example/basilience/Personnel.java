package com.example.basilience;

public class Personnel {
    private String id;    // Firestore document id (we use farmerUid as doc id)
    private String name;
    private String role;
    private String email;
    private String phone;

    // Required empty constructor for Firestore
    public Personnel() {}

    public Personnel(String id, String name, String role, String email, String phone) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.email = email;
        this.phone = phone;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getRole() { return role; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }

    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setRole(String role) { this.role = role; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
}