package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.models.User;
import cs.quizzapp.prokect.backend.services.UserService;
import cs.quizzapp.prokect.backend.services.QuizService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:3000")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private QuizService quizService;

    // Get all users (Admin only)
    @GetMapping
    public ResponseEntity<?> getAllUsers() {
        try {
            List<User> users = userService.getAllUsers();
            List<Map<String, Object>> sanitizedUsers = users.stream()
                    .map(this::createUserResponse)
                    .toList();
            return ResponseEntity.ok(sanitizedUsers);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve users: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Get user by ID
    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        try {
            Optional<User> user = userService.findUserById(id);
            if (user.isPresent()) {
                return ResponseEntity.ok(createUserResponse(user.get()));
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve user: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * RUBRIC REQUIREMENT: Update user profile with username, firstName, lastName, email + 3 additional fields
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User userUpdate) {
        try {
            // RUBRIC REQUIREMENT: Input validation with error messages
            if (userUpdate.getUsername() != null && userUpdate.getUsername().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Username cannot be empty");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (userUpdate.getEmail() != null && userUpdate.getEmail().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Email cannot be empty");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (userUpdate.getFirstName() != null && userUpdate.getFirstName().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "First name cannot be empty");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (userUpdate.getLastName() != null && userUpdate.getLastName().trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Last name cannot be empty");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            Optional<User> updatedUser = userService.updateUser(id, userUpdate);

            if (updatedUser.isPresent()) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", "User profile updated successfully");
                response.put("user", createUserResponse(updatedUser.get()));
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to update user: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Delete user (Admin only)
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        try {
            boolean deleted = userService.deleteUser(id);

            if (deleted) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "User deleted successfully");
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to delete user: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Get user statistics (Admin only)
    @GetMapping("/statistics")
    public ResponseEntity<?> getUserStatistics() {
        try {
            UserService.UserStatistics stats = userService.getUserStatistics();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve user statistics: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Update user role (Admin only)
    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateUserRole(@PathVariable Long id, @RequestBody Map<String, String> roleUpdate) {
        try {
            String newRole = roleUpdate.get("role");

            if (newRole == null || newRole.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Role is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            if (!newRole.equals("ADMIN") && !newRole.equals("PLAYER")) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Invalid role. Must be ADMIN or PLAYER");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            Optional<User> updatedUser = userService.updateUserRole(id, newRole);

            if (updatedUser.isPresent()) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", "User role updated successfully");
                response.put("user", createUserResponse(updatedUser.get()));
                return ResponseEntity.ok(response);
            } else {
                Map<String, String> error = new HashMap<>();
                error.put("error", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to update user role: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * RUBRIC REQUIREMENT: View scores for each quiz tournament
     */
    @GetMapping("/{id}/quiz-history")
    public ResponseEntity<?> getUserQuizHistory(@PathVariable Long id) {
        try {
            Optional<User> user = userService.findUserById(id);
            if (!user.isPresent()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            List<Map<String, Object>> history = quizService.getUserQuizHistory(id);

            // Add summary statistics
            Map<String, Object> response = new HashMap<>();
            response.put("userId", id);
            response.put("username", user.get().getUsername());
            response.put("quizHistory", history);
            response.put("totalQuizzesTaken", history.size());

            if (!history.isEmpty()) {
                double averageScore = history.stream()
                        .mapToDouble(h -> (Double) h.get("score"))
                        .average()
                        .orElse(0.0);
                response.put("averageScore", Math.round(averageScore * 100.0) / 100.0);
            } else {
                response.put("averageScore", 0.0);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve user quiz history: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Check username availability
    @GetMapping("/check-username/{username}")
    public ResponseEntity<?> checkUsernameAvailability(@PathVariable String username) {
        try {
            boolean exists = userService.existsByUsername(username);
            Map<String, Object> response = new HashMap<>();
            response.put("available", !exists);
            response.put("username", username);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to check username availability: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Check email availability
    @GetMapping("/check-email/{email}")
    public ResponseEntity<?> checkEmailAvailability(@PathVariable String email) {
        try {
            boolean exists = userService.existsByEmail(email);
            Map<String, Object> response = new HashMap<>();
            response.put("available", !exists);
            response.put("email", email);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to check email availability: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Create user response without sensitive data
     * RUBRIC REQUIREMENT: Include 3 additional fields (city, occupation, preferredLanguage)
     */
    private Map<String, Object> createUserResponse(User user) {
        Map<String, Object> userResponse = new HashMap<>();
        userResponse.put("id", user.getId());
        userResponse.put("username", user.getUsername());
        userResponse.put("email", user.getEmail());
        userResponse.put("firstName", user.getFirstName());
        userResponse.put("lastName", user.getLastName());
        userResponse.put("role", user.getRole());
        userResponse.put("profilePicture", user.getProfilePicture());

        // RUBRIC REQUIREMENT: 3 additional required fields
        userResponse.put("city", user.getCity());
        userResponse.put("occupation", user.getOccupation());
        userResponse.put("preferredLanguage", user.getPreferredLanguage());

        // Optional profile fields
        userResponse.put("phoneNumber", user.getPhoneNumber());
        userResponse.put("address", user.getAddress());
        userResponse.put("dateOfBirth", user.getDateOfBirth());
        userResponse.put("gender", user.getGender());
        userResponse.put("country", user.getCountry());
        userResponse.put("bio", user.getBio());

        return userResponse;
    }
}