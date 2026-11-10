package com.ben.services.service.impl;

import com.ben.common_lib.enums.PaymentGateway;
import com.ben.common_lib.enums.PaymentStatus;
import com.ben.common_lib.payload.request.PaymentInitiateRequest;
import com.ben.common_lib.payload.response.PaymentInitiateResponse;
import com.ben.common_lib.payload.response.VnPayReturnResponse;
import com.ben.services.event.PaymentEventProducer;
import com.ben.services.model.Payment;
import com.ben.services.repository.PaymentRepository;
import com.ben.services.service.gateway.VnPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The VNPay result rules (RspCode, amount, duplicates) and the one-payment-per-booking rule.
// Two IPNs at the same moment are proven on a real database in VnPayIpnConcurrencyTest.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceImplTest {

    private static final Long BOOKING_ID = 42L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventProducer paymentEventProducer;

    @Mock
    private VnPayService vnPayService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @BeforeEach
    void signatureValidAndTransactionsRunTheCallback() {
        when(vnPayService.isValidSignature(anyMap())).thenReturn(true);
        when(vnPayService.toVnpAmount(any())).thenAnswer(invocation -> Math.round((Double) invocation.getArgument(0)) * 100);
        when(vnPayService.createPaymentUrl(any(), anyString())).thenReturn("https://vnpay/pay?signed");
        when(vnPayService.expiresAt(any())).thenReturn(Instant.parse("2026-12-25T01:10:00Z"));
        when(transactionTemplate.execute(any())).thenAnswer(this::runCallback);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── IPN ─────────────────────────────────────────────────────────────

    @Test
    void aPaidIpnMarksThePaymentSuccessAndQueuesTheEvent() {
        Payment payment = pending();
        when(paymentRepository.findByTransactionIdForUpdate("TXN1")).thenReturn(Optional.of(payment));

        Map<String, String> answer = paymentService.handleVnPayIpn(ipn("00", "00", "15000000"));

        assertThat(answer).containsEntry("RspCode", "00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getProviderPaymentId()).isEqualTo("14000001");
        assertThat(payment.getPaidAt()).isNotNull();
        verify(paymentEventProducer).sendPaymentCompleted(payment);
        verify(paymentEventProducer, never()).sendPaymentFailed(any());
    }

    @Test
    void aDeclinedIpnMarksThePaymentFailedWithTheReason() {
        Payment payment = pending();
        when(paymentRepository.findByTransactionIdForUpdate("TXN1")).thenReturn(Optional.of(payment));

        Map<String, String> answer = paymentService.handleVnPayIpn(ipn("51", "02", "15000000"));

        // VNPay still gets "00": we recorded the result, so it must stop retrying
        assertThat(answer).containsEntry("RspCode", "00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).contains("51");
        verify(paymentEventProducer).sendPaymentFailed(payment);
        verify(paymentEventProducer, never()).sendPaymentCompleted(any());
    }

    @Test
    void aSuccessCodeWithoutASuccessfulTransactionStatusIsNotPaid() {
        Payment payment = pending();
        when(paymentRepository.findByTransactionIdForUpdate("TXN1")).thenReturn(Optional.of(payment));

        paymentService.handleVnPayIpn(ipn("00", "01", "15000000"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentEventProducer, never()).sendPaymentCompleted(any());
    }

    @Test
    void anIpnWithABadSignatureIsRejectedBeforeAnyLookup() {
        when(vnPayService.isValidSignature(anyMap())).thenReturn(false);

        assertThat(paymentService.handleVnPayIpn(ipn("00", "00", "15000000"))).containsEntry("RspCode", "97");
        verifyNoInteractions(paymentRepository, paymentEventProducer);
    }

    @Test
    void anIpnForAnUnknownOrderAnswers01() {
        when(paymentRepository.findByTransactionIdForUpdate("TXN1")).thenReturn(Optional.empty());

        assertThat(paymentService.handleVnPayIpn(ipn("00", "00", "15000000"))).containsEntry("RspCode", "01");
        verifyNoInteractions(paymentEventProducer);
    }

    @Test
    void anIpnWithAnotherAmountChangesNothing() {
        Payment payment = pending();
        when(paymentRepository.findByTransactionIdForUpdate("TXN1")).thenReturn(Optional.of(payment));

        assertThat(paymentService.handleVnPayIpn(ipn("00", "00", "100"))).containsEntry("RspCode", "04");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(paymentEventProducer);
    }

    @Test
    void aRepeatedIpnForAFinishedPaymentAnswers02AndSendsNothing() {
        Payment payment = pending();
        payment.setStatus(PaymentStatus.SUCCESS);
        when(paymentRepository.findByTransactionIdForUpdate("TXN1")).thenReturn(Optional.of(payment));

        assertThat(paymentService.handleVnPayIpn(ipn("00", "00", "15000000"))).containsEntry("RspCode", "02");
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentEventProducer);
    }

    // ── Return URL ──────────────────────────────────────────────────────

    @Test
    void aCancelOnTheVnPayPageIsRecordedFromTheReturnRedirect() {
        Payment payment = pending();
        when(paymentRepository.findByTransactionIdForUpdate("TXN1")).thenReturn(Optional.of(payment));

        VnPayReturnResponse result = paymentService.handleVnPayReturn(ipn("24", "02", "15000000"));

        // VNPay usually sends no IPN for a cancel, so the booking is closed and its seats freed from here
        assertThat(result.isSuccess()).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentEventProducer).sendPaymentFailed(payment);
    }

    @Test
    void aSuccessfulReturnIsDisplayOnlyAndWaitsForTheIpn() {
        VnPayReturnResponse result = paymentService.handleVnPayReturn(ipn("00", "00", "15000000"));

        assertThat(result.isValidSignature()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        verifyNoInteractions(paymentRepository, paymentEventProducer);
    }

    @Test
    void aForgedReturnIsNeitherSuccessNorRecorded() {
        when(vnPayService.isValidSignature(anyMap())).thenReturn(false);

        VnPayReturnResponse result = paymentService.handleVnPayReturn(ipn("24", "02", "15000000"));

        assertThat(result.isValidSignature()).isFalse();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Invalid signature");
        verifyNoInteractions(paymentRepository, paymentEventProducer);
    }

    // ── Initiate ────────────────────────────────────────────────────────

    @Test
    void initiateCreatesAPendingPaymentWithAVnPayLink() throws Exception {
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        PaymentInitiateResponse result = paymentService.initiatePayment(initiateRequest());

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
        // Used as vnp_TxnRef, which VNPay wants alphanumeric
        assertThat(saved.getValue().getTransactionId()).matches("TXN[0-9A-Z]+");
        assertThat(result.getCheckoutUrl()).isEqualTo("https://vnpay/pay?signed");
        assertThat(result.getExpiresAt()).isEqualTo(Instant.parse("2026-12-25T01:10:00Z"));
    }

    @Test
    void aRepeatedInitiateReturnsTheSameLinkInsteadOfASecondPayment() throws Exception {
        Payment existing = pending();
        existing.setCheckoutUrl("https://vnpay/pay?first");
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(existing));

        PaymentInitiateResponse result = paymentService.initiatePayment(initiateRequest());

        assertThat(result.getCheckoutUrl()).isEqualTo("https://vnpay/pay?first");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiateForAnAlreadyPaidBookingIs409() {
        Payment paid = pending();
        paid.setStatus(PaymentStatus.SUCCESS);
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> paymentService.initiatePayment(initiateRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void losingTheInsertRaceReturnsTheWinnersPayment() throws Exception {
        Payment winner = pending();
        winner.setCheckoutUrl("https://vnpay/pay?winner");
        // First transaction loses the race on UNIQUE booking_id, the second re-reads the winner
        doThrow(new DataIntegrityViolationException("duplicate booking_id"))
                .doAnswer(this::runCallback)
                .when(transactionTemplate).execute(any());
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(winner));

        PaymentInitiateResponse result = paymentService.initiatePayment(initiateRequest());

        assertThat(result.getCheckoutUrl()).isEqualTo("https://vnpay/pay?winner");
    }

    @Test
    void lookingUpPaymentsWithoutIdsCallsNothing() {
        assertThat(paymentService.getPaymentsByBookingIds(List.of())).isEmpty();
        assertThat(paymentService.getPaymentsByBookingIds(null)).isEmpty();
        verifyNoInteractions(paymentRepository);
    }

    private Object runCallback(org.mockito.invocation.InvocationOnMock invocation) {
        TransactionCallback<?> callback = invocation.getArgument(0);
        return callback.doInTransaction(null);
    }

    private static Payment pending() {
        return Payment.builder()
                .id(7L)
                .bookingId(BOOKING_ID)
                .amount(150_000.0)
                .provider(PaymentGateway.VNPAY)
                .status(PaymentStatus.PENDING)
                .transactionId("TXN1")
                .build();
    }

    private static PaymentInitiateRequest initiateRequest() {
        PaymentInitiateRequest request = new PaymentInitiateRequest();
        request.setUserId(3L);
        request.setBookingId(BOOKING_ID);
        request.setGateway(PaymentGateway.VNPAY);
        request.setAmount(150_000.0);
        return request;
    }

    private static Map<String, String> ipn(String responseCode, String transactionStatus, String amount) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "TXN1");
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", transactionStatus);
        params.put("vnp_Amount", amount);
        params.put("vnp_TransactionNo", "14000001");
        params.put("vnp_CardType", "ATM");
        params.put("vnp_SecureHash", "abc");
        return params;
    }
}
