package cs.quizzapp.prokect.backend.controllers;

import cs.quizzapp.prokect.backend.services.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/email")
@CrossOrigin(origins = "http://localhost:3000")
public class EmailTestController {

    @Autowired
    private EmailService emailService;

    // Test email configuration without sending actual email
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testEmailConfiguration() {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean configValid = emailService.testEmailConfiguration();
            Map<String, Object> status = emailService.getEmailServiceStatus();

            response.put("success", true);
            response.put("configurationValid", configValid);
            response.put("status", status);
            response.put("message", "Email configuration test completed");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Email configuration test failed: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // Send actual test email
    @PostMapping("/test-send")
    public ResponseEntity<Map<String, Object>> sendTestEmail(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String testEmail = request.get("email");
            if (testEmail == null || testEmail.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "Email address is required");
                return ResponseEntity.badRequest().body(response);
            }

            emailService.sendTestEmail(testEmail);
            response.put("success", true);
            response.put("message", "Test email sent successfully to " + testEmail);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to send test email: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // Get email service status
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getEmailServiceStatus() {
        try {
            Map<String, Object> status = emailService.getEmailServiceStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get email service status: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}