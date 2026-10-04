package com.campus.marketplace.service;

import com.campus.marketplace.config.EmailProperties;
import com.campus.marketplace.config.OtpProperties;
import com.campus.marketplace.exception.EmailDeliveryException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MailServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final MailService mailService = new MailService(
            mailSender, new EmailProperties("no-reply@seu.edu.bd"), new OtpProperties(Duration.ofMinutes(10), 5, Duration.ofSeconds(60)));

    @Test
    void sendOtpEmail_sendsPlainTextMessageWithCodeAndExpiry() {
        mailService.sendOtpEmail("alice@seu.edu.bd", "004217");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();

        assertThat(message.getFrom()).isEqualTo("no-reply@seu.edu.bd");
        assertThat(message.getTo()).containsExactly("alice@seu.edu.bd");
        assertThat(message.getSubject()).isEqualTo(MailService.OTP_SUBJECT);
        assertThat(message.getText())
                .contains("004217")
                .contains("expires in 10 minutes");
    }

    @Test
    void sendOtpEmail_wrapsSmtpFailure() {
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> mailService.sendOtpEmail("alice@seu.edu.bd", "123456"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageNotContaining("123456");
    }
}
