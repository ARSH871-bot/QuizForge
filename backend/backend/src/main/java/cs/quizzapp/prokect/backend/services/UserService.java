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

    // Delete a user by ID
    public boolean deleteUser(Long id) {
        if (userRepository.existsById(id)) {
            userRepository.deleteById(id);
            return true;
        }
        return false;
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