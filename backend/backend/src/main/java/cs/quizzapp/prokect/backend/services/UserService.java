package cs.quizzapp.prokect.backend.services;

import cs.quizzapp.prokect.backend.db.UserRepository;
import cs.quizzapp.prokect.backend.models.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.User.UserBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // Save user
    public User save(User user) {
        return userRepository.save(user);
    }

    // === Password Reset ===
    public String requestPasswordReset(String username) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            String resetToken = UUID.randomUUID().toString();
            user.setPasswordResetToken(resetToken);
            userRepository.save(user);

            try {
                emailService.sendPasswordResetEmail(user.getEmail(), user.getUsername(), resetToken);
            } catch (Exception e) {
                System.err.println("Failed to send password reset email: " + e.getMessage());
            }
            return resetToken;
        }
        throw new IllegalArgumentException("User with the given username not found.");
    }

    public boolean resetPasswordWithUsername(String username, String token, String newPassword) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            if (user.getPasswordResetToken() != null && user.getPasswordResetToken().equals(token)) {
                user.setPassword(passwordEncoder.encode(newPassword));
                user.setPasswordResetToken(null);
                userRepository.save(user);
                return true;
            }
        }
        return false;
    }

    // === Authentication ===
    public boolean authenticate(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("Invalid password");
        }
        return true;
    }

    // === Registration ===
    public User registerUser(User user) {
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.findByEmail(user.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getRole() == null || user.getRole().isEmpty()) {
            user.setRole("PLAYER");
        }

        User savedUser = userRepository.save(user);

        try {
            emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getUsername());
        } catch (Exception e) {
            System.err.println("Failed to send welcome email: " + e.getMessage());
        }

        return savedUser;
    }

    public User registerAdmin(User adminUser) {
        if (userRepository.findByUsername(adminUser.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.findByEmail(adminUser.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        if (adminUser.getFirstName() == null || adminUser.getFirstName().trim().isEmpty()) {
            throw new IllegalArgumentException("First name is required for admin users");
        }
        if (adminUser.getLastName() == null || adminUser.getLastName().trim().isEmpty()) {
            throw new IllegalArgumentException("Last name is required for admin users");
        }
        if (adminUser.getPassword() == null || adminUser.getPassword().length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters long");
        }

        adminUser.setPassword(passwordEncoder.encode(adminUser.getPassword()));
        adminUser.setRole("ADMIN");

        User savedAdmin = userRepository.save(adminUser);

        try {
            emailService.sendAdminWelcomeEmail(savedAdmin.getEmail(), savedAdmin.getUsername());
        } catch (Exception e) {
            System.err.println("Failed to send admin welcome email: " + e.getMessage());
        }

        return savedAdmin;
    }

    // === Role and user management ===
    public List<User> getUsersByRole(String role) {
        return userRepository.findByRole(role);
    }

    public List<User> getAllPlayers() {
        return getUsersByRole("PLAYER");
    }

    public List<User> getAllAdmins() {
        return getUsersByRole("ADMIN");
    }

    public boolean existsByUsername(String username) {
        return userRepository.findByUsername(username).isPresent();
    }

    public boolean existsByEmail(String email) {
        return userRepository.findByEmail(email).isPresent();
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public long countUsersByRole(String role) {
        return getUsersByRole(role).size();
    }

    public Optional<User> updateUserRole(Long userId, String newRole) {
        return userRepository.findById(userId).map(user -> {
            if (!newRole.equals("ADMIN") && !newRole.equals("PLAYER")) {
                throw new IllegalArgumentException("Invalid role. Must be ADMIN or PLAYER");
            }
            user.setRole(newRole);
            User updatedUser = userRepository.save(user);

            try {
                emailService.sendRoleChangeNotification(user.getEmail(), user.getUsername(), newRole);
            } catch (Exception e) {
                System.err.println("Failed to send role change notification: " + e.getMessage());
            }

            return updatedUser;
        });
    }

    public boolean deleteUser(Long id) {
        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isPresent()) {
            User user = userOptional.get();

            if ("ADMIN".equals(user.getRole())) {
                long adminCount = countUsersByRole("ADMIN");
                if (adminCount <= 1) {
                    throw new IllegalArgumentException("Cannot delete the last admin user");
                }
            }

            try {
                emailService.sendAccountDeletionNotification(user.getEmail(), user.getUsername());
            } catch (Exception e) {
                System.err.println("Failed to send deletion notification: " + e.getMessage());
            }

            userRepository.deleteById(id);
            return true;
        }
        return false;
    }

    public Optional<User> findUserById(Long id) {
        return userRepository.findById(id);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    // === Enhanced profile update (includes rubric fields) ===
    public Optional<User> updateUser(Long id, User user) {
        return userRepository.findById(id).map(existingUser -> {
            // Username & email uniqueness checks
            if (user.getUsername() != null && !user.getUsername().equals(existingUser.getUsername())) {
                if (userRepository.findByUsername(user.getUsername()).isPresent()) {
                    throw new IllegalArgumentException("Username already exists");
                }
                existingUser.setUsername(user.getUsername());
            }

            if (user.getEmail() != null && !user.getEmail().equals(existingUser.getEmail())) {
                if (userRepository.findByEmail(user.getEmail()).isPresent()) {
                    throw new IllegalArgumentException("Email already exists");
                }
                existingUser.setEmail(user.getEmail());
            }

            // Basic fields
            if (user.getFirstName() != null) existingUser.setFirstName(user.getFirstName());
            if (user.getLastName() != null) existingUser.setLastName(user.getLastName());
            if (user.getProfilePicture() != null) existingUser.setProfilePicture(user.getProfilePicture());

            // ✅ Required rubric fields
            if (user.getPhoneNumber() != null) existingUser.setPhoneNumber(user.getPhoneNumber());
            if (user.getCity() != null) existingUser.setCity(user.getCity());
            if (user.getOccupation() != null) existingUser.setOccupation(user.getOccupation());
            if (user.getPreferredLanguage() != null) existingUser.setPreferredLanguage(user.getPreferredLanguage());

            // ✅ Optional profile fields
            if (user.getAddress() != null) existingUser.setAddress(user.getAddress());
            if (user.getDateOfBirth() != null) existingUser.setDateOfBirth(user.getDateOfBirth());
            if (user.getGender() != null) existingUser.setGender(user.getGender());
            if (user.getCountry() != null) existingUser.setCountry(user.getCountry());
            if (user.getBio() != null) existingUser.setBio(user.getBio());

            // Password update
            if (user.getPassword() != null && !user.getPassword().isEmpty()) {
                if (user.getPassword().length() < 6) {
                    throw new IllegalArgumentException("Password must be at least 6 characters long");
                }
                existingUser.setPassword(passwordEncoder.encode(user.getPassword()));
            }

            return userRepository.save(existingUser);
        });
    }

    public void validateUserData(User user, boolean isNewUser) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (isNewUser && (user.getPassword() == null || user.getPassword().length() < 6)) {
            throw new IllegalArgumentException("Password must be at least 6 characters long");
        }

        String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        if (!user.getEmail().matches(emailRegex)) {
            throw new IllegalArgumentException("Invalid email format");
        }

        String usernameRegex = "^[a-zA-Z0-9_]{3,20}$";
        if (!user.getUsername().matches(usernameRegex)) {
            throw new IllegalArgumentException("Username must be 3-20 characters and contain only letters, numbers, and underscores");
        }
    }

    public UserStatistics getUserStatistics() {
        List<User> allUsers = getAllUsers();
        long totalUsers = allUsers.size();
        long adminCount = allUsers.stream().filter(u -> "ADMIN".equals(u.getRole())).count();
        long playerCount = allUsers.stream().filter(u -> "PLAYER".equals(u.getRole())).count();

        return new UserStatistics(totalUsers, adminCount, playerCount);
    }

    public List<User> searchUsers(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return getAllUsers();
        }

        String lowerSearchTerm = searchTerm.toLowerCase();
        return getAllUsers().stream()
                .filter(user ->
                        user.getUsername().toLowerCase().contains(lowerSearchTerm) ||
                                user.getEmail().toLowerCase().contains(lowerSearchTerm) ||
                                (user.getFirstName() != null && user.getFirstName().toLowerCase().contains(lowerSearchTerm)) ||
                                (user.getLastName() != null && user.getLastName().toLowerCase().contains(lowerSearchTerm))
                )
                .toList();
    }

    // === UserDetailsService implementation ===
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        UserBuilder builder = org.springframework.security.core.userdetails.User.withUsername(user.getUsername());
        builder.password(user.getPassword());
        builder.roles(user.getRole());

        return builder.build();
    }

    // DTO for admin dashboard
    public static class UserStatistics {
        private final long totalUsers;
        private final long adminCount;
        private final long playerCount;

        public UserStatistics(long totalUsers, long adminCount, long playerCount) {
            this.totalUsers = totalUsers;
            this.adminCount = adminCount;
            this.playerCount = playerCount;
        }

        public long getTotalUsers() { return totalUsers; }
        public long getAdminCount() { return adminCount; }
        public long getPlayerCount() { return playerCount; }
    }
}
