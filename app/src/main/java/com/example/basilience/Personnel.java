package com.example.basilience;

public class Personnel {
    private String id;    // Firestore document id (farmerUid)
    private String fullName;
    private String role;
    private String email;
    private String phone;

    public Personnel() {}

    public Personnel(String id, String fullName, String role, String email, String phone) {
        this.id = id;
        this.fullName = fullName;
        this.role = role;
        this.email = email;
        this.phone = phone;
    }

    public String getId() { return id; }
    public String getFullName() { return fullName; }
    public String getRole() { return role; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }

    public void setId(String id) { this.id = id; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setRole(String role) { this.role = role; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
}
