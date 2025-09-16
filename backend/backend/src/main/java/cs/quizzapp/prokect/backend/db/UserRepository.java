package cs.quizzapp.prokect.backend.db;

import cs.quizzapp.prokect.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);

    // NEW METHOD - Required for UserService role-based queries
    List<User> findByRole(String role);
}