package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.models.User;
import cs.quizzapp.prokect.backend.services.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:3000")
public class AuthController {

    @Autowired
    private UserService userService;

    // Login - Enhanced with proper validation
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            String usernameOrEmail = body.get("username");
            String password = body.get("password");

            // Input validation
            if (usernameOrEmail == null || usernameOrEmail.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Username or email is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (password == null || password.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Password is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            // Authenticate user
            User user = userService.authenticateUser(usernameOrEmail, password);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Login successful");
            response.put("user", createUserResponse(user));

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "An unexpected error occurred during login");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Register Player - Enhanced validation
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User user) {
        try {
            // Input validation
            if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Username is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Email is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (user.getFirstName() == null || user.getFirstName().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "First name is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (user.getLastName() == null || user.getLastName().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Last name is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (user.getPassword() == null || user.getPassword().length() < 6) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Password must be at least 6 characters long");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            // Set default role to PLAYER
            user.setRole("PLAYER");

            User registeredUser = userService.registerUser(user);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "User registered successfully. Please log in.");
            response.put("user", createUserResponse(registeredUser));

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "An unexpected error occurred during registration");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Register Admin - NEW ENDPOINT
    @PostMapping("/register-admin")
    public ResponseEntity<?> registerAdmin(@RequestBody User adminUser) {
        try {
            // Input validation
            if (adminUser.getUsername() == null || adminUser.getUsername().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Username is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (adminUser.getEmail() == null || adminUser.getEmail().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Email is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (adminUser.getFirstName() == null || adminUser.getFirstName().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "First name is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (adminUser.getLastName() == null || adminUser.getLastName().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Last name is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (adminUser.getPassword() == null || adminUser.getPassword().length() < 6) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Password must be at least 6 characters long");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            User registeredAdmin = userService.registerAdmin(adminUser);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Admin user created successfully");
            response.put("user", createUserResponse(registeredAdmin));

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "An unexpected error occurred during admin creation");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Password reset request - Enhanced validation
    @PostMapping("/request-password-reset")
    public ResponseEntity<?> requestPasswordReset(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");

            if (email == null || email.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Email is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            String resetToken = userService.requestPasswordResetByEmail(email);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Password reset instructions sent to email: " + email);
            response.put("token", resetToken); // For testing purposes

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "An unexpected error occurred during password reset request");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Reset password - Enhanced validation
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            String token = body.get("token");
            String newPassword = body.get("newPassword");

            // Input validation
            if (email == null || email.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Email is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (token == null || token.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Reset token is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (newPassword == null || newPassword.length() < 6) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "New password must be at least 6 characters long");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            boolean isReset = userService.resetPasswordWithEmail(email, token, newPassword);

            if (isReset) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Password reset successfully. You can now log in with your new password.");
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Invalid token or token expired. Please request a new password reset.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "An unexpected error occurred during password reset");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Logout - Enhanced
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body) {
        try {
            String username = body.get("username");

            Map<String, String> response = new HashMap<>();
            if (username != null && !username.trim().isEmpty()) {
                response.put("message", "Logout successful for user: " + username);
            } else {
                response.put("message", "Logout successful");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "An unexpected error occurred during logout");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Helper method to create user response without sensitive data
    private Map<String, Object> createUserResponse(User user) {
        Map<String, Object> userResponse = new HashMap<>();
        userResponse.put("id", user.getId());
        userResponse.put("username", user.getUsername());
        userResponse.put("email", user.getEmail());
        userResponse.put("firstName", user.getFirstName());
        userResponse.put("lastName", user.getLastName());
        userResponse.put("role", user.getRole());
        userResponse.put("profilePicture", user.getProfilePicture());
        userResponse.put("city", user.getCity());
        userResponse.put("occupation", user.getOccupation());
        userResponse.put("preferredLanguage", user.getPreferredLanguage());
        return userResponse;
    }
}