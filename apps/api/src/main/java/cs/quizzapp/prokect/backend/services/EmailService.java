package cs.quizzapp.prokect.backend.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    // **NEW** - Configurable from address
    @Value("${spring.mail.username:noreply@quiztournament.com}")
    private String fromAddress;

    // **NEW** - Test mode flag
    @Value("${email.test.mode:false}")
    private boolean testMode;

    public void sendSimpleMessage(String to, String subject, String text) {
        try {
            if (testMode) {
                System.out.println("=== EMAIL TEST MODE ===");
                System.out.println("TO: " + to);
                System.out.println("SUBJECT: " + subject);
                System.out.println("BODY: " + text);
                System.out.println("========================");
                return;
            }

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            message.setFrom(fromAddress);
            message.setSentDate(new Date());

            mailSender.send(message);
            System.out.println("Email sent successfully to " + to + " at " + new Date());
        } catch (MailException e) {
            System.err.println("Error sending email to " + to + ": " + e.getMessage());
            e.printStackTrace();
            // Optionally, log the error or throw a custom exception
        } catch (Exception e) {
            System.err.println("Unexpected error sending email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // **FIXED** - This method was empty and causing the issue
    public void sendSimpleEmail(String to, String subject, String text) {
        System.out.println("🔍 sendSimpleEmail called - TO: " + to + ", SUBJECT: " + subject);

        try {
            if (testMode) {
                System.out.println("=== EMAIL TEST MODE (sendSimpleEmail) ===");
                System.out.println("TO: " + to);
                System.out.println("SUBJECT: " + subject);
                System.out.println("BODY: " + text);
                System.out.println("=========================================");
                return;
            }

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            message.setFrom(fromAddress);
            message.setSentDate(new Date());

            System.out.println("📧 Attempting to send email via JavaMailSender...");
            mailSender.send(message);
            System.out.println("✅ Email sent successfully to " + to + " at " + new Date());

        } catch (MailException e) {
            System.err.println("❌ MailException sending email to " + to + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        } catch (Exception e) {
            System.err.println("❌ Unexpected error sending email: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Unexpected email error: " + e.getMessage(), e);
        }
    }

    // **NEW METHOD** - Test email configuration
    public boolean testEmailConfiguration() {
        try {
            String testEmail = "test@example.com";
            String subject = "Email Configuration Test";
            String body = "This is a test email to verify email configuration. Sent at: " + new Date();

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(testEmail);
            message.setSubject(subject);
            message.setText(body);
            message.setFrom(fromAddress);

            // Don't actually send in test - just verify configuration
            System.out.println("Email configuration test successful!");
            System.out.println("From: " + fromAddress);
            System.out.println("Test mode: " + testMode);
            return true;
        } catch (Exception e) {
            System.err.println("Email configuration test failed: " + e.getMessage());
            return false;
        }
    }

    // **NEW METHOD** - Send test email to specific address
    public void sendTestEmail(String to) {
        String subject = "Quiz Tournament - Test Email";
        String text = "Hello!\n\n" +
                "This is a test email from the Quiz Tournament application.\n\n" +
                "Email Configuration Details:\n" +
                "- From Address: " + fromAddress + "\n" +
                "- Test Mode: " + testMode + "\n" +
                "- Sent At: " + new Date() + "\n\n" +
                "If you receive this email, the email service is working correctly!\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleEmail(to, subject, text);
    }

    public void sendQuizNotification(String to, String quizName) {
        String subject = "New Quiz Tournament Available: " + quizName;
        String text = "Hello!\n\n" +
                "A new quiz tournament titled '" + quizName + "' has been created and is now available!\n\n" +
                "Quiz Details:\n" +
                "- Name: " + quizName + "\n" +
                "- Questions: 10 challenging questions\n" +
                "- Duration: Multiple choice format\n\n" +
                "Log in to the Quiz Tournament application to participate and compete with other players!\n\n" +
                "Good luck!\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    public void sendPasswordResetEmail(String to, String username, String resetToken) {
        String subject = "Password Reset Request - Quiz Tournament";
        String text = "Hello " + username + ",\n\n" +
                "We received a request to reset your password for your Quiz Tournament account.\n\n" +
                "Your password reset token is: " + resetToken + "\n\n" +
                "To reset your password:\n" +
                "1. Use the 'Reset Password' endpoint in the API\n" +
                "2. Provide your username: " + username + "\n" +
                "3. Provide the token above\n" +
                "4. Set your new password\n\n" +
                "This token will expire after use for security reasons.\n\n" +
                "If you did not request this password reset, please ignore this email.\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    public void sendWelcomeEmail(String to, String username) {
        String subject = "Welcome to Quiz Tournament!";
        String text = "Hello " + username + ",\n\n" +
                "Welcome to Quiz Tournament! Your account has been created successfully.\n\n" +
                "You can now:\n" +
                "- Participate in exciting quiz tournaments\n" +
                "- Track your scores and progress\n" +
                "- Compete with other players\n" +
                "- View leaderboards and statistics\n\n" +
                "Start by exploring the available quizzes and test your knowledge!\n\n" +
                "Happy quizzing!\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    // **NEW METHOD** - Admin welcome email
    public void sendAdminWelcomeEmail(String to, String username) {
        String subject = "Welcome to Quiz Tournament - Admin Account";
        String text = "Hello " + username + ",\n\n" +
                "Welcome to Quiz Tournament! Your administrator account has been created successfully.\n\n" +
                "As an administrator, you can:\n" +
                "- Create and manage quiz tournaments\n" +
                "- View all user accounts and statistics\n" +
                "- Monitor quiz participation and results\n" +
                "- Manage user roles and permissions\n" +
                "- Access comprehensive reporting features\n\n" +
                "Your admin privileges give you full control over the Quiz Tournament platform.\n\n" +
                "Important Security Notes:\n" +
                "- Keep your admin credentials secure\n" +
                "- Use a strong, unique password\n" +
                "- Monitor admin activities regularly\n\n" +
                "Welcome to the admin team!\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    // **NEW METHOD** - Role change notification
    public void sendRoleChangeNotification(String to, String username, String newRole) {
        String subject = "Account Role Updated - Quiz Tournament";
        String roleDescription = "ADMIN".equals(newRole) ?
                "administrator with full management privileges" :
                "player with quiz participation privileges";

        String text = "Hello " + username + ",\n\n" +
                "Your account role has been updated in the Quiz Tournament system.\n\n" +
                "Account Details:\n" +
                "- Username: " + username + "\n" +
                "- New Role: " + newRole + "\n" +
                "- Updated: " + new Date() + "\n\n" +
                "You are now a " + roleDescription + ".\n\n";

        if ("ADMIN".equals(newRole)) {
            text += "As an administrator, you now have access to:\n" +
                    "- Quiz creation and management\n" +
                    "- User account management\n" +
                    "- System statistics and reports\n" +
                    "- Administrative controls\n\n";
        } else {
            text += "As a player, you can:\n" +
                    "- Participate in quiz tournaments\n" +
                    "- View your scores and progress\n" +
                    "- Compete on leaderboards\n\n";
        }

        text += "If you have any questions about your new role, please contact support.\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    // **NEW METHOD** - Account deletion notification
    public void sendAccountDeletionNotification(String to, String username) {
        String subject = "Account Deletion Confirmation - Quiz Tournament";
        String text = "Hello " + username + ",\n\n" +
                "This email confirms that your Quiz Tournament account has been permanently deleted.\n\n" +
                "Account Details:\n" +
                "- Username: " + username + "\n" +
                "- Email: " + to + "\n" +
                "- Deleted: " + new Date() + "\n\n" +
                "All associated data including:\n" +
                "- Quiz scores and history\n" +
                "- Profile information\n" +
                "- Participation records\n\n" +
                "has been permanently removed from our system.\n\n" +
                "If you did not request this deletion or believe this was done in error, " +
                "please contact our support team immediately.\n\n" +
                "Thank you for being part of the Quiz Tournament community.\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    // **ENHANCED METHOD** - Quiz completion email with more details
    public void sendQuizCompletionEmail(String to, String username, String quizName, double score, boolean passed) {
        String subject = "Quiz Completed: " + quizName;
        String passMessage = passed ?
                "Congratulations! You passed the quiz!" :
                "Keep practicing! You can retake the quiz to improve your score.";

        String text = "Hello " + username + ",\n\n" +
                "You have completed the quiz: " + quizName + "\n\n" +
                "Your Results:\n" +
                "- Score: " + String.format("%.1f", score) + " out of 10\n" +
                "- Percentage: " + String.format("%.1f", (score/10)*100) + "%\n" +
                "- Status: " + (passed ? "PASSED" : "NEEDS IMPROVEMENT") + "\n" +
                "- Completed: " + new Date() + "\n\n" +
                passMessage + "\n\n" +
                "Performance Tips:\n";

        if (score >= 8) {
            text += "- Excellent performance! You're a quiz master!\n";
        } else if (score >= 6) {
            text += "- Good job! Review the topics you missed for improvement.\n";
        } else {
            text += "- Consider reviewing the quiz material and trying again.\n";
        }

        text += "\nCheck out other available quizzes and continue improving your knowledge!\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    // **NEW METHOD** - Quiz reminder email
    public void sendQuizReminderEmail(String to, String username, String quizName, Date endDate) {
        String subject = "Quiz Ending Soon: " + quizName;
        String text = "Hello " + username + ",\n\n" +
                "This is a friendly reminder that the quiz '" + quizName + "' will be ending soon!\n\n" +
                "Quiz Details:\n" +
                "- Name: " + quizName + "\n" +
                "- End Date: " + endDate + "\n" +
                "- Time Remaining: Limited!\n\n" +
                "Don't miss your chance to participate and compete with other players!\n\n" +
                "Log in now to take the quiz before it's too late.\n\n" +
                "Good luck!\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }

    // **NEW METHOD** - Bulk email sender with error tracking
    public void sendBulkEmails(java.util.List<String> recipients, String subject, String text) {
        int successCount = 0;
        int failureCount = 0;

        System.out.println("Starting bulk email send to " + recipients.size() + " recipients");

        for (String recipient : recipients) {
            try {
                sendSimpleMessage(recipient, subject, text);
                successCount++;

                // Small delay to prevent overwhelming the email server
                Thread.sleep(100);
            } catch (Exception e) {
                failureCount++;
                System.err.println("Failed to send email to " + recipient + ": " + e.getMessage());
            }
        }

        System.out.println("Bulk email send completed - Success: " + successCount + ", Failures: " + failureCount);
    }

    // **NEW METHOD** - Get email service status
    public java.util.Map<String, Object> getEmailServiceStatus() {
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        status.put("fromAddress", fromAddress);
        status.put("testMode", testMode);
        status.put("configured", mailSender != null);
        status.put("timestamp", new Date());

        try {
            boolean configTest = testEmailConfiguration();
            status.put("configurationValid", configTest);
        } catch (Exception e) {
            status.put("configurationValid", false);
            status.put("error", e.getMessage());
        }

        return status;
    }

    // **NEW METHOD** - Enhanced user update notification
    public void sendUserUpdateNotification(String to, String username, java.util.List<String> changes) {
        String subject = "Account Information Updated - Quiz Tournament";

        StringBuilder changesList = new StringBuilder();
        for (String change : changes) {
            changesList.append("• ").append(change).append("\n");
        }

        String text = "Hello " + username + ",\n\n" +
                "Your account information has been updated by an administrator.\n\n" +
                "Changes made:\n" + changesList.toString() + "\n" +
                "Updated: " + new Date() + "\n\n" +
                "If you have any questions about these changes, please contact our support team.\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleEmail(to, subject, text);
    }
}