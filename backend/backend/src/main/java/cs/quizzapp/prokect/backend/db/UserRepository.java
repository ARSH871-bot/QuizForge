package cs.quizzapp.prokect.backend.db;

import cs.quizzapp.prokect.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by email address
     * @param email the email to search for
     * @return Optional containing the user if found
     */
    Optional<User> findByEmail(String email);

    /**
     * Find user by username
     * @param username the username to search for
     * @return Optional containing the user if found
     */
    Optional<User> findByUsername(String username);

    /**
     * Find users by role (ADMIN, PLAYER)
     * @param role the role to filter by
     * @return List of users with the specified role
     */
    List<User> findByRole(String role);

    /**
     * Check if username already exists
     * @param username the username to check
     * @return true if username exists, false otherwise
     */
    boolean existsByUsername(String username);

    /**
     * Check if email already exists
     * @param email the email to check
     * @return true if email exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Find user by password reset token
     * @param resetToken the reset token to search for
     * @return Optional containing the user if found
     */
    Optional<User> findByPasswordResetToken(String resetToken);

    /**
     * Find users by first name containing (case insensitive)
     * @param firstName the first name to search for
     * @return List of users with matching first names
     */
    List<User> findByFirstNameContainingIgnoreCase(String firstName);

    /**
     * Find users by last name containing (case insensitive)
     * @param lastName the last name to search for
     * @return List of users with matching last names
     */
    List<User> findByLastNameContainingIgnoreCase(String lastName);

    /**
     * Find users by username containing (case insensitive)
     * @param username the username to search for
     * @return List of users with matching usernames
     */
    List<User> findByUsernameContainingIgnoreCase(String username);

    /**
     * Find users by email containing (case insensitive)
     * @param email the email to search for
     * @return List of users with matching emails
     */
    List<User> findByEmailContainingIgnoreCase(String email);

    /**
     * Count users by role
     * @param role the role to count
     * @return number of users with the specified role
     */
    long countByRole(String role);

    /**
     * Find users by country
     * @param country the country to filter by
     * @return List of users from the specified country
     */
    List<User> findByCountry(String country);

    /**
     * Custom query to search users by multiple criteria
     * @param searchTerm the term to search for across multiple fields
     * @return List of users matching the search criteria
     */
    @Query("SELECT u FROM User u WHERE " +
            "LOWER(u.username) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(u.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<User> searchUsers(@Param("searchTerm") String searchTerm);

    /**
     * Find users by role and country
     * @param role the role to filter by
     * @param country the country to filter by
     * @return List of users matching both criteria
     */
    List<User> findByRoleAndCountry(String role, String country);

    /**
     * Find all users ordered by username
     * @return List of all users sorted by username
     */
    List<User> findAllByOrderByUsernameAsc();

    /**
     * Find all users ordered by creation date (if you have a createdAt field)
     * @return List of all users sorted by creation date
     */
    // List<User> findAllByOrderByCreatedAtDesc();

    /**
     * Custom query to find users excluding a specific user ID
     * Useful for checking username/email uniqueness during updates
     * @param username the username to check
     * @param userId the user ID to exclude from the check
     * @return Optional containing user if username exists for different user
     */
    @Query("SELECT u FROM User u WHERE u.username = :username AND u.id != :userId")
    Optional<User> findByUsernameAndNotId(@Param("username") String username, @Param("userId") Long userId);

    /**
     * Custom query to find users by email excluding a specific user ID
     * @param email the email to check
     * @param userId the user ID to exclude from the check
     * @return Optional containing user if email exists for different user
     */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.id != :userId")
    Optional<User> findByEmailAndNotId(@Param("email") String email, @Param("userId") Long userId);
}