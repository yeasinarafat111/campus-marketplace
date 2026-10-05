package com.campus.marketplace.service;

import com.campus.marketplace.config.EmailProperties;
import com.campus.marketplace.config.OtpProperties;
import com.campus.marketplace.exception.EmailDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    static final String OTP_SUBJECT = "Your Campus Marketplace verification code";

    private static final String OTP_TEMPLATE = """
            Hello,

            Your Campus Marketplace verification code is:

                %s

            This code expires in %d minutes. Do not share it with anyone.

            If you did not request this code, you can safely ignore this email.

            — Campus Marketplace
            """;

    static final String RESET_SUBJECT = "Reset your Campus Marketplace password";
    private static final String RESET_TEMPLATE = """
            Hello,

            We received a request to reset your Campus Marketplace password. Your reset code is:

                %s

            This code expires in %d minutes. Do not share it with anyone.

            If you did not request a password reset, you can safely ignore this email. Your password will not change.

            — Campus Marketplace
            """;

    private final JavaMailSender mailSender;
    private final EmailProperties emailProperties;
    private final OtpProperties otpProperties;

    /**
     * Sends a plain-text email containing a one-time code. Blocks until the SMTP server accepts the message.
     *
     * @throws EmailDeliveryException if the SMTP server can't be reached or rejects the message
     */
    public void sendOtpEmail(String to, String code) {
        send(to, OTP_SUBJECT, OTP_TEMPLATE, code, "verification email");
    }

    /** Same delivery as {@link #sendOtpEmail}, with password-reset wording. */
    public void sendPasswordResetEmail(String to, String code) {
        send(to, RESET_SUBJECT, RESET_TEMPLATE, code, "password reset email");
    }

    private void send(String to, String subject, String template, String code, String description) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(emailProperties.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(template.formatted(code, otpProperties.expiration().toMinutes()));
        try {
            mailSender.send(message);
            log.info("Sent {} to {}", description, to);
        } catch (MailException e) {
            // Never log the code itself.
            log.error("Failed to send {} to {}: {}", description, to, e.getMessage());
            throw new EmailDeliveryException("Could not send " + description, e);
        }
    }
}
