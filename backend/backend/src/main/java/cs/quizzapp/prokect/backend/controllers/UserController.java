package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.models.Quiz;
import cs.quizzapp.prokect.backend.models.User;
import cs.quizzapp.prokect.backend.payload.LoginRequest;
import cs.quizzapp.prokect.backend.payload.PasswordResetRequest;
import cs.quizzapp.prokect.backend.services.QuizService;
import cs.quizzapp.prokect.backend.services.UserService;
import cs.quizzapp.prokect.backend.utils.QuizCategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:3000")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private QuizService quizService;

    // User Registration
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody User user) {
        Map<String, Object> response = new HashMap<>();
        try {
            User registeredUser = userService.registerUser(user);
            response.put("success", true);
            response.put("message", "User registered successfully");
            response.put("user", Map.of(
                    "id", registeredUser.getId(),
                    "username", registeredUser.getUsername(),
                    "email", registeredUser.getEmail(),
                    "role", registeredUser.getRole()
            ));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Registration failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // User Login
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> loginUser(@RequestBody LoginRequest loginRequest) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean authenticated = userService.authenticate(loginRequest.getUsername(), loginRequest.getPassword());
            if (authenticated) {
                Optional<User> userOptional = userService.findByUsername(loginRequest.getUsername());
                if (userOptional.isPresent()) {
                    User user = userOptional.get();
                    response.put("success", true);
                    response.put("message", "Login successful");
                    response.put("user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "email", user.getEmail(),
                            "role", user.getRole(),
                            "firstName", user.getFirstName() != null ? user.getFirstName() : "",
                            "lastName", user.getLastName() != null ? user.getLastName() : ""
                    ));
                    return ResponseEntity.ok(response);
                }
            }
            response.put("success", false);
            response.put("message", "Invalid credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        } catch (UsernameNotFoundException e) {
            response.put("success", false);
            response.put("message", "User not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Login failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // NEW - Admin Creation Endpoint
    @PostMapping("/admin/create-admin")
    public ResponseEntity<Map<String, Object>> createAdminUser(@RequestBody User adminUser) {
        Map<String, Object> response = new HashMap<>();
        try {
            User createdAdmin = userService.registerAdmin(adminUser);
            response.put("success", true);
            response.put("message", "Admin user created successfully");
            response.put("admin", Map.of(
                    "id", createdAdmin.getId(),
                    "username", createdAdmin.getUsername(),
                    "email", createdAdmin.getEmail(),
                    "role", createdAdmin.getRole(),
                    "firstName", createdAdmin.getFirstName(),
                    "lastName", createdAdmin.getLastName()
            ));
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Admin creation failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Password Reset Request
    @PostMapping("/password/reset-request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(@RequestBody Map<String, String> request) {
        Map<String, String> response = new HashMap<>();
        try {
            String username = request.get("username");
            if (username == null || username.trim().isEmpty()) {
                response.put("success", "false");
                response.put("message", "Username is required");
                return ResponseEntity.badRequest().body(response);
            }

            String token = userService.requestPasswordReset(username);
            response.put("success", "true");
            response.put("message", "Password reset email sent successfully");
            response.put("token", token);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", "false");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", "false");
            response.put("message", "Password reset request failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Password Reset
    @PostMapping("/password/reset")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody PasswordResetRequest resetRequest) {
        Map<String, String> response = new HashMap<>();
        try {
            boolean success = userService.resetPasswordWithUsername(
                    resetRequest.getUsername(),
                    resetRequest.getToken(),
                    resetRequest.getNewPassword()
            );

            if (success) {
                response.put("success", "true");
                response.put("message", "Password reset successful");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", "false");
                response.put("message", "Invalid token or username");
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            response.put("success", "false");
            response.put("message", "Password reset failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Get User Profile
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getUserProfile(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<User> userOptional = userService.findUserById(id);
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                response.put("success", true);
                response.put("user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "firstName", user.getFirstName() != null ? user.getFirstName() : "",
                        "lastName", user.getLastName() != null ? user.getLastName() : "",
                        "role", user.getRole(),
                        "phoneNumber", user.getPhoneNumber() != null ? user.getPhoneNumber() : "",
                        "address", user.getAddress() != null ? user.getAddress() : "",
                        "country", user.getCountry() != null ? user.getCountry() : "",
                        "bio", user.getBio() != null ? user.getBio() : ""
                ));
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch user profile: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Update User Profile
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateUserProfile(@PathVariable Long id, @RequestBody User updatedUser) {
        Map<String, Object> response = new HashMap<>();
        try {
            Optional<User> userOptional = userService.updateUser(id, updatedUser);
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                response.put("success", true);
                response.put("message", "Profile updated successfully");
                response.put("user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "firstName", user.getFirstName() != null ? user.getFirstName() : "",
                        "lastName", user.getLastName() != null ? user.getLastName() : "",
                        "role", user.getRole()
                ));
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Profile update failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ADMIN ENDPOINTS

    // Get All Users (Admin only)
    @GetMapping("/admin/all-users")
    public ResponseEntity<Map<String, Object>> getAllUsers() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<User> users = userService.getAllUsers();
            List<Map<String, Object>> userList = new ArrayList<>();

            for (User user : users) {
                Map<String, Object> userMap = new HashMap<>();
                userMap.put("id", user.getId());
                userMap.put("username", user.getUsername());
                userMap.put("email", user.getEmail());
                userMap.put("firstName", user.getFirstName() != null ? user.getFirstName() : "");
                userMap.put("lastName", user.getLastName() != null ? user.getLastName() : "");
                userMap.put("role", user.getRole());
                userMap.put("phoneNumber", user.getPhoneNumber() != null ? user.getPhoneNumber() : "");
                userMap.put("country", user.getCountry() != null ? user.getCountry() : "");
                userList.add(userMap);
            }

            response.put("success", true);
            response.put("users", userList);
            response.put("totalUsers", users.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch users: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Get Users by Role (Admin only)
    @GetMapping("/admin/users-by-role/{role}")
    public ResponseEntity<Map<String, Object>> getUsersByRole(@PathVariable String role) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<User> users = userService.getUsersByRole(role.toUpperCase());
            response.put("success", true);
            response.put("users", users);
            response.put("count", users.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch users by role: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Update User Role (Admin only)
    @PutMapping("/admin/users/{userId}/role")
    public ResponseEntity<Map<String, Object>> updateUserRole(@PathVariable Long userId, @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String newRole = request.get("role");
            if (newRole == null || newRole.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "Role is required");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<User> userOptional = userService.updateUserRole(userId, newRole.toUpperCase());
            if (userOptional.isPresent()) {
                User user = userOptional.get();
                response.put("success", true);
                response.put("message", "User role updated successfully");
                response.put("user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "role", user.getRole()
                ));
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Role update failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Delete User (Admin only)
    @DeleteMapping("/admin/users/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean deleted = userService.deleteUser(userId);
            if (deleted) {
                response.put("success", true);
                response.put("message", "User deleted successfully");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "User deletion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // QUIZ-RELATED ENDPOINTS

    // Get Available Quizzes for User
    @GetMapping("/{userId}/available-quizzes")
    public ResponseEntity<List<Map<String, Object>>> getAvailableQuizzes(@PathVariable Long userId) {
        try {
            List<Quiz> ongoingQuizzes = quizService.getOngoingQuizzes();
            List<Map<String, Object>> quizSummaries = new ArrayList<>();

            for (Quiz quiz : ongoingQuizzes) {
                Map<String, Object> summary = new HashMap<>();
                summary.put("id", quiz.getId());
                summary.put("name", quiz.getName());
                summary.put("category", quiz.getCategory());
                summary.put("difficulty", quiz.getDifficulty());
                summary.put("startDate", quiz.getStartDate());
                summary.put("endDate", quiz.getEndDate());
                summary.put("minimumPassingScore", quiz.getMinimumPassingScore());
                summary.put("likesCount", quiz.getLikesCount());
                summary.put("rating", quiz.getRating());

                // Check if user can participate
                Map<String, Object> eligibility = quizService.canUserParticipateInQuiz(quiz.getId(), userId);
                summary.put("canParticipate", eligibility.get("canParticipate"));
                summary.put("participationReason", eligibility.get("reason"));

                quizSummaries.add(summary);
            }

            return ResponseEntity.ok(quizSummaries);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ArrayList<>());
        }
    }

    // Get User's Quiz History
    @GetMapping("/{userId}/quiz-history")
    public ResponseEntity<List<Map<String, Object>>> getUserQuizHistory(@PathVariable Long userId) {
        try {
            List<Map<String, Object>> history = quizService.getUserQuizHistory(userId);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ArrayList<>());
        }
    }

    // Get Quiz Categories
    @GetMapping("/categories")
    public ResponseEntity<List<Map<String, Object>>> getQuizCategories() {
        try {
            // Get categories as Map and convert to List of Maps
            Map<String, Integer> categoriesMap = QuizCategoryMapper.getAllCategories();
            List<Map<String, Object>> categories = new ArrayList<>();

            for (Map.Entry<String, Integer> entry : categoriesMap.entrySet()) {
                Map<String, Object> category = new HashMap<>();
                category.put("name", entry.getKey());
                category.put("id", entry.getValue());
                categories.add(category);
            }

            return ResponseEntity.ok(categories);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ArrayList<>());
        }
    }

    // QUIZ STATUS ENDPOINTS (Admin)

    // Get Quiz Status (Admin only)
    @GetMapping("/admin/quizzes/{quizId}/status")
    public ResponseEntity<Map<String, Object>> getQuizStatus(@PathVariable Long quizId) {
        try {
            Map<String, Object> status = quizService.getQuizStatus(quizId);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get quiz status: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Get User Participation Status (Admin only)
    @GetMapping("/admin/quizzes/{quizId}/user/{userId}/participation-status")
    public ResponseEntity<Map<String, Object>> getUserParticipationStatus(@PathVariable Long quizId, @PathVariable Long userId) {
        try {
            Map<String, Object> status = quizService.getUserParticipationStatus(quizId, userId);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get participation status: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}