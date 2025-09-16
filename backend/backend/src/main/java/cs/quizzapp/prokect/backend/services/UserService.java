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

    // Request password reset by generating a token using username
    public String requestPasswordReset(String username) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();

            // Generate a reset token (UUID for simplicity)
            String resetToken = UUID.randomUUID().toString();
            user.setPasswordResetToken(resetToken);
            userRepository.save(user);

            // Send password reset email
            try {
                emailService.sendPasswordResetEmail(user.getEmail(), user.getUsername(), resetToken);
                System.out.println("Password reset email sent to: " + user.getEmail());
            } catch (Exception e) {
                System.err.println("Failed to send password reset email: " + e.getMessage());
            }

            return resetToken; // Return the reset token to be displayed to the user in the app
        }
        throw new IllegalArgumentException("User with the given username not found.");
    }

    // Reset password using the token and username
    public boolean resetPasswordWithUsername(String username, String token, String newPassword) {
        Optional<User> userOptional = userRepository.findByUsername(username);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            if (user.getPasswordResetToken() != null && user.getPasswordResetToken().equals(token)) {
                user.setPassword(passwordEncoder.encode(newPassword)); // Hash the password
                user.setPasswordResetToken(null); // Clear the reset token after successful reset
                userRepository.save(user);
                return true;
            }
        }
        return false;
    }

    // Authentication (Login)
    public boolean authenticate(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("Invalid password");
        }
        return true; // Authentication success
    }

    // Registration with welcome email
    public User registerUser(User user) {
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.findByEmail(user.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        user.setPassword(passwordEncoder.encode(user.getPassword())); // Encrypt password
        if (user.getRole() == null || user.getRole().isEmpty()) {
            user.setRole("PLAYER");  // Assign default role
        }

        User savedUser = userRepository.save(user);

        // Send welcome email
        try {
            emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getUsername());
            System.out.println("Welcome email sent to: " + savedUser.getEmail());
        } catch (Exception e) {
            System.err.println("Failed to send welcome email: " + e.getMessage());
        }

        return savedUser;
    }

    // **NEW METHOD** - Admin Registration with enhanced validation
    public User registerAdmin(User adminUser) {
        if (userRepository.findByUsername(adminUser.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.findByEmail(adminUser.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        // Validate admin-specific requirements
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
        adminUser.setRole("ADMIN"); // Force admin role

        User savedAdmin = userRepository.save(adminUser);

        // Send admin welcome email
        try {
            emailService.sendAdminWelcomeEmail(savedAdmin.getEmail(), savedAdmin.getUsername());
            System.out.println("Admin welcome email sent to: " + savedAdmin.getEmail());
        } catch (Exception e) {
            System.err.println("Failed to send admin welcome email: " + e.getMessage());
        }

        return savedAdmin;
    }

    // **NEW METHOD** - Get users by role
    public List<User> getUsersByRole(String role) {
        return userRepository.findByRole(role);
    }

    // **NEW METHOD** - Get all players
    public List<User> getAllPlayers() {
        return getUsersByRole("PLAYER");
    }

    // **NEW METHOD** - Get all admins
    public List<User> getAllAdmins() {
        return getUsersByRole("ADMIN");
    }

    // **NEW METHOD** - Check if user exists by username
    public boolean existsByUsername(String username) {
        return userRepository.findByUsername(username).isPresent();
    }

    // **NEW METHOD** - Check if user exists by email
    public boolean existsByEmail(String email) {
        return userRepository.findByEmail(email).isPresent();
    }

    // **NEW METHOD** - Find user by username
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    // **NEW METHOD** - Find user by email
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    // **NEW METHOD** - Count users by role
    public long countUsersByRole(String role) {
        return getUsersByRole(role).size();
    }

    // **NEW METHOD** - Update user role (admin function)
    public Optional<User> updateUserRole(Long userId, String newRole) {
        return userRepository.findById(userId).map(user -> {
            // Validate role
            if (!newRole.equals("ADMIN") && !newRole.equals("PLAYER")) {
                throw new IllegalArgumentException("Invalid role. Must be ADMIN or PLAYER");
            }

            user.setRole(newRole);
            User updatedUser = userRepository.save(user);

            // Send role change notification
            try {
                emailService.sendRoleChangeNotification(user.getEmail(), user.getUsername(), newRole);
                System.out.println("Role change notification sent to: " + user.getEmail());
            } catch (Exception e) {
                System.err.println("Failed to send role change notification: " + e.getMessage());
            }

            return updatedUser;
        });
    }

    // **ENHANCED METHOD** - Delete user with validation
    public boolean deleteUser(Long id) {
        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isPresent()) {
            User user = userOptional.get();

            // Check if this is the last admin - prevent deletion if so
            if ("ADMIN".equals(user.getRole())) {
                long adminCount = countUsersByRole("ADMIN");
                if (adminCount <= 1) {
                    throw new IllegalArgumentException("Cannot delete the last admin user");
                }
            }

            // Send account deletion notification
            try {
                emailService.sendAccountDeletionNotification(user.getEmail(), user.getUsername());
                System.out.println("Account deletion notification sent to: " + user.getEmail());
            } catch (Exception e) {
                System.err.println("Failed to send deletion notification: " + e.getMessage());
            }

            userRepository.deleteById(id);
            return true;
        }
        return false;
    }

    // Fetch a user by ID
    public Optional<User> findUserById(Long id) {
        return userRepository.findById(id);
    }

    // Get all users
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    // Update an existing user with enhanced profile fields
    public Optional<User> updateUser(Long id, User user) {
        return userRepository.findById(id).map(existingUser -> {
            existingUser.setUsername(user.getUsername() != null ? user.getUsername() : existingUser.getUsername());
            existingUser.setEmail(user.getEmail() != null ? user.getEmail() : existingUser.getEmail());
            existingUser.setFirstName(user.getFirstName() != null ? user.getFirstName() : existingUser.getFirstName());
            existingUser.setLastName(user.getLastName() != null ? user.getLastName() : existingUser.getLastName());
            existingUser.setPhoneNumber(user.getPhoneNumber() != null ? user.getPhoneNumber() : existingUser.getPhoneNumber());
            existingUser.setAddress(user.getAddress() != null ? user.getAddress() : existingUser.getAddress());
            existingUser.setDateOfBirth(user.getDateOfBirth() != null ? user.getDateOfBirth() : existingUser.getDateOfBirth());
            existingUser.setGender(user.getGender() != null ? user.getGender() : existingUser.getGender());
            existingUser.setCountry(user.getCountry() != null ? user.getCountry() : existingUser.getCountry());
            existingUser.setBio(user.getBio() != null ? user.getBio() : existingUser.getBio());
            existingUser.setProfilePicture(user.getProfilePicture() != null ? user.getProfilePicture() : existingUser.getProfilePicture());

            if (user.getPassword() != null && !user.getPassword().isEmpty()) {
                existingUser.setPassword(passwordEncoder.encode(user.getPassword()));
            }
            return userRepository.save(existingUser);
        });
    }

    // Implementation for UserDetailsService
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Fetch user from the database
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        // Build UserDetails object
        UserBuilder builder = org.springframework.security.core.userdetails.User.withUsername(user.getUsername());
        builder.password(user.getPassword());
        builder.roles(user.getRole());

        return builder.build();
    }
}