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
            mailSender.send(message);
            System.out.println("Email sent successfully to " + to);
        } catch (MailException e) {
            System.err.println("Error sending email: " + e.getMessage());
            // Optionally, log the error or throw a custom exception
        }
    }

    public void sendQuizNotification(String to, String quizName) {
        String subject = "New Quiz Available!";
        String text = "A new quiz titled '" + quizName + "' has been added. Check it out and participate!";
        sendSimpleMessage(to, subject, text);
    }
}
