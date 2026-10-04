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

    private final JavaMailSender mailSender;
    private final EmailProperties emailProperties;
    private final OtpProperties otpProperties;

    /**
     * Sends a plain-text email containing a one-time code. Blocks until the SMTP server accepts the message.
     *
     * @throws EmailDeliveryException if the SMTP server can't be reached or rejects the message
     */
    public void sendOtpEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(emailProperties.from());
        message.setTo(to);
        message.setSubject(OTP_SUBJECT);
        message.setText(OTP_TEMPLATE.formatted(code, otpProperties.expiration().toMinutes()));

        try {
            mailSender.send(message);
            log.info("Sent OTP email to {}", to);
        } catch (MailException e) {
            // Never log the code itself.
            log.error("Failed to send OTP email to {}: {}", to, e.getMessage());
            throw new EmailDeliveryException("Could not send verification email", e);
        }
    }
}
