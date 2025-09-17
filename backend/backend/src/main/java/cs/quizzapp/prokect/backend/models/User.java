package cs.quizzapp.prokect.backend.models;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column
    private String passwordResetToken;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;

    private String firstName;
    private String lastName;
    private String profilePicture;

    // REQUIRED: Three additional profile fields (as per rubric)
    private String phoneNumber;
    private String city;                // Field 1
    private String occupation;          // Field 2
    private String preferredLanguage;   // Field 3

    // Optional additional profile fields
    private String address;

    @JsonFormat(pattern = "yyyy-MM-dd") // ensures correct JSON serialization
    private LocalDate dateOfBirth;

    private String gender;
    private String country;

    @Column(length = 1000) // allow longer text
    private String bio;

    public User() {}

    public User(String username, String email, String password, String role,
                String firstName, String lastName, String profilePicture) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.role = role;
        this.firstName = firstName;
        this.lastName = lastName;
        this.profilePicture = profilePicture;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getPasswordResetToken() { return passwordResetToken; }
    public void setPasswordResetToken(String passwordResetToken) { this.passwordResetToken = passwordResetToken; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getProfilePicture() { return profilePicture; }
    public void setProfilePicture(String profilePicture) { this.profilePicture = profilePicture; }

    // Required new fields
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getOccupation() { return occupation; }
    public void setOccupation(String occupation) { this.occupation = occupation; }

    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }

    // Optional profile fields
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
}
