package com.ben.service.listener;

import com.ben.common_lib.event.BookingConfirmedEvent;
import com.ben.service.service.EmailService;
import com.ben.service.service.NotificationDedupe;
import com.ben.service.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingNotificationListener {

    private final EmailService emailService;
    private final SmsService smsService;
    private final NotificationDedupe dedupe;

    @KafkaListener(
            topics = "booking.confirmed",
            groupId = "notification-service-group",
            containerFactory = "bookingConfirmedKafkaListenerContainerFactory"
    )
    public void handleBookingConfirmed(@Payload BookingConfirmedEvent event) {
        log.info("Received BookingConfirmedEvent: bookingRef={}, email={}",
                event.getBookingReference(), event.getContactEmail());

        if (event.getContactEmail() != null && !event.getContactEmail().isBlank()) {
            sendOnce("email", event, () -> emailService.sendBookingConfirmation(event));
        } else {
            log.warn("No contact email for booking={}, skipping email", event.getBookingReference());
        }

        if (event.getContactPhone() != null && !event.getContactPhone().isBlank()) {
            sendOnce("sms", event, () -> smsService.sendBookingConfirmation(event));
        } else {
            log.warn("No contact phone for booking={}, skipping SMS", event.getBookingReference());
        }
    }

    /**
     * Sends on this channel at most once per booking. A failed send frees the claim and rethrows, so Kafka
     * retries with backoff and finally parks the message in booking.confirmed.DLT (never silently lost).
     */
    private void sendOnce(String channel, BookingConfirmedEvent event, Sender sender) {
        if (!dedupe.claim(channel, event.getBookingId())) {
            log.info("{} for booking={} already sent, skipping duplicate", channel, event.getBookingReference());
            return;
        }
        try {
            sender.send();
            log.info("{} sent for booking={}", channel, event.getBookingReference());
        } catch (Exception e) {
            dedupe.release(channel, event.getBookingId());
            throw new IllegalStateException(channel + " for booking " + event.getBookingReference() + " failed", e);
        }
    }

    // EmailService.sendBookingConfirmation throws checked exceptions, so a plain Runnable won't do
    @FunctionalInterface
    private interface Sender {
        void send() throws Exception;
    }
}
