package cs.quizzapp.prokect.backend.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    public void sendSimpleMessage(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            message.setFrom("noreply@quiztournament.com"); // Set from address
            mailSender.send(message);
            System.out.println("Email sent successfully to " + to);
        } catch (MailException e) {
            System.err.println("Error sending email: " + e.getMessage());
            // Optionally, log the error or throw a custom exception
        }
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

    public void sendQuizCompletionEmail(String to, String username, String quizName, double score, boolean passed) {
        String subject = "Quiz Completed: " + quizName;
        String passMessage = passed ? "Congratulations! You passed the quiz!" : "Keep practicing! You can retake the quiz to improve your score.";

        String text = "Hello " + username + ",\n\n" +
                "You have completed the quiz: " + quizName + "\n\n" +
                "Your Results:\n" +
                "- Score: " + String.format("%.1f", score) + " out of 10\n" +
                "- Status: " + (passed ? "PASSED" : "NEEDS IMPROVEMENT") + "\n\n" +
                passMessage + "\n\n" +
                "Check out other available quizzes and continue improving your knowledge!\n\n" +
                "Best regards,\n" +
                "Quiz Tournament Team";

        sendSimpleMessage(to, subject, text);
    }
}