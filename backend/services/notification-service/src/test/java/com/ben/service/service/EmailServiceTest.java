package com.ben.service.service;

import com.ben.common_lib.event.BookingConfirmedEvent;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;

import java.time.LocalDateTime;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Turns a booking.confirmed event into the confirmation email; the template only shows what is prepared here
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private TemplateEngine templateEngine;

    @InjectMocks
    private EmailService emailService;

    private final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));

    @BeforeEach
    void mailAndTemplateAnswer() {
        ReflectionTestUtils.setField(emailService, "fromEmail", "noreply@ben.test");
        ReflectionTestUtils.setField(emailService, "fromName", "Ben Airline");
        when(mailSender.createMimeMessage()).thenReturn(message);
        when(templateEngine.process(eq("email/booking-confirmation"), any(IContext.class))).thenReturn("<p>ok</p>");
    }

    @Test
    void sendsToTheContactWithTheBookingInTheSubject() throws Exception {
        emailService.sendBookingConfirmation(event());

        verify(mailSender).send(message);
        assertThat(message.getRecipients(Message.RecipientType.TO))
                .extracting(address -> ((InternetAddress) address).getAddress())
                .containsExactly("traveller@ben.test");
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("noreply@ben.test");
        assertThat(message.getSubject()).isEqualTo("Booking Confirmed | BEN42 | SGN→HAN | 25 Dec 2026");
    }

    @Test
    void fillsTheTemplateWithFormattedDatesPricesAndBaggage() throws Exception {
        emailService.sendBookingConfirmation(event());

        Context context = renderedContext();
        assertThat(context.getVariable("depTime")).isEqualTo("08:00");
        assertThat(context.getVariable("arrDate")).isEqualTo("25 Dec 2026");
        assertThat(context.getVariable("totalAmount")).isEqualTo("1500000.00");
        assertThat(context.getVariable("checkinBaggage")).isEqualTo("1 × 23 kg");
        assertThat(context.getVariable("cabinBaggage")).isEqualTo("Not included");
        assertThat(context.getVariable("hasBaggage")).isEqualTo(true);
        assertThat(context.getVariable("cabinClassDisplay")).isEqualTo("Premium Economy");
    }

    @Test
    void missingOptionalFieldsShowDefaultsInsteadOfFailing() throws Exception {
        BookingConfirmedEvent event = BookingConfirmedEvent.builder()
                .bookingReference("BEN43")
                .contactEmail("traveller@ben.test")
                .build();

        emailService.sendBookingConfirmation(event);

        Context context = renderedContext();
        assertThat(context.getVariable("depDate")).isEqualTo("N/A");
        assertThat(context.getVariable("paidAt")).isEqualTo("N/A");
        assertThat(context.getVariable("totalAmount")).isEqualTo("0.00");
        assertThat(context.getVariable("hasBaggage")).isEqualTo(false);
        assertThat(context.getVariable("cabinClassDisplay")).isEqualTo("Economy");
        assertThat(context.getVariable("passengerCount")).isEqualTo(1);
    }

    private Context renderedContext() {
        ArgumentCaptor<Context> context = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("email/booking-confirmation"), context.capture());
        return context.getValue();
    }

    private static BookingConfirmedEvent event() {
        return BookingConfirmedEvent.builder()
                .bookingId(42L)
                .bookingReference("BEN42")
                .contactEmail("traveller@ben.test")
                .departureAirportCode("SGN")
                .arrivalAirportCode("HAN")
                .departureDateTime(LocalDateTime.of(2026, 12, 25, 8, 0))
                .arrivalDateTime(LocalDateTime.of(2026, 12, 25, 10, 5))
                .totalAmount(1_500_000.0)
                .checkinBaggagePieces(1)
                .checkinBaggageWeightPerPiece(23.0)
                .cabinClass("PREMIUM_ECONOMY")
                .build();
    }
}
