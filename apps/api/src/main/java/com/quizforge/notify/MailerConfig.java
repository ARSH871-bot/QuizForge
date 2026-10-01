package com.quizforge.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Chooses how mail leaves the application.
 *
 * <p>SMTP when {@code SPRING_MAIL_HOST} is configured - Mailpit locally, any
 * provider in production. Otherwise mail is not delivered, which is logged
 * loudly once at startup and quietly per message, and the message body is
 * never logged: it carries links that grant access to an account.
 */
@Configuration
class MailerConfig {

    private static final Logger log = LoggerFactory.getLogger(MailerConfig.class);

    @Bean
    Mailer mailer(ObjectProvider<JavaMailSender> smtp,
                  @Value("${quizforge.mail.from:QuizForge <no-reply@localhost>}") String from) {
        JavaMailSender sender = smtp.getIfAvailable();
        Executor background = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mail");
            t.setDaemon(true);
            return t;
        });

        if (sender == null) {
            log.warn("Email is not configured (SPRING_MAIL_HOST is unset): password reset "
                    + "emails will not be delivered.");
            return mail -> log.info("Email not delivered, no mail server configured: {}", mail);
        }

        return mail -> background.execute(() -> {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(mail.to());
                message.setSubject(mail.subject());
                message.setText(mail.text());
                sender.send(message);
            } catch (RuntimeException e) {
                // Logged, not thrown: the request that asked for this has
                // already answered, and must not reveal whether mail was sent.
                log.error("Could not send {}", mail, e);
            }
        });
    }
}
