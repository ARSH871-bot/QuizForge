package cs.quizzapp.prokect.backend;

import cs.quizzapp.prokect.backend.models.User;
import cs.quizzapp.prokect.backend.db.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
public class BackendApplication implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

    @Override
    public void run(String... args) {
        // Check if admin user exists, if not, create it
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User(
                    "admin",
                    "admin@quizapp.com",
                    passwordEncoder.encode("op@1234"), // Fixed password as per requirements
                    "ADMIN",
                    "Admin",
                    "User",
                    null
            );
            userRepository.save(admin);
            System.out.println("Admin user created: Username: admin, Password: op@1234");
        } else {
            System.out.println("Admin user already exists.");
        }

        // Check if player user exists, if not, create it
        if (userRepository.findByUsername("player").isEmpty()) {
            User player = new User(
                    "player",
                    "player@example.com",
                    passwordEncoder.encode("Player@123"),
                    "PLAYER",
                    "Player",
                    "User",
                    null
            );
            userRepository.save(player);
            System.out.println("Player user created: Username: player, Password: Player@123");
        } else {
            System.out.println("Player user already exists.");
        }
    }
}