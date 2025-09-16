package cs.quizzapp.prokect.backend.payload;

public class PasswordResetRequest {
    private String username;
    private String token;
    private String newPassword;

    // Default constructor
    public PasswordResetRequest() {}

    // Constructor with parameters
    public PasswordResetRequest(String username, String token, String newPassword) {
        this.username = username;
        this.token = token;
        this.newPassword = newPassword;
    }

    // Getters and setters
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}