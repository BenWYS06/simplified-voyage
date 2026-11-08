package com.ben.services;

import com.ben.common_lib.enums.PaymentGateway;
import com.ben.common_lib.enums.PaymentStatus;
import com.ben.services.model.Payment;
import com.ben.services.outbox.OutboxRepository;
import com.ben.services.repository.PaymentRepository;
import com.ben.services.service.PaymentService;
import com.ben.services.support.Concurrently;
import com.ben.services.support.IntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build steps 3 + 6 (DESIGN.md §6): VNPay may deliver the same IPN twice at once. SELECT ... FOR UPDATE makes
 * the copies queue; the PENDING check lets only the first one record the result, so the payment becomes
 * SUCCESS once and exactly ONE payment.completed event goes into the outbox.
 */
class VnPayIpnConcurrencyTest extends IntegrationTest {

    private static final String HASH_SECRET = "test-secret";   // = vnpay.hash-secret in IntegrationTest

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @RepeatedTest(5)
    void sameIpnTwiceAtTheSameMoment_isProcessedOnce() throws Exception {
        long bookingId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        Payment payment = paymentRepository.save(Payment.builder()
                .userId(1L)
                .bookingId(bookingId)
                .amount(500_000.0)
                .provider(PaymentGateway.VNPAY)
                .status(PaymentStatus.PENDING)
                .transactionId("TXN-" + UUID.randomUUID())
                .build());
        Map<String, String> ipn = signedIpn(payment, "00");

        List<Object> outcomes = Concurrently.run(List.of(
                () -> paymentService.handleVnPayIpn(ipn),
                () -> paymentService.handleVnPayIpn(ipn)));

        // one copy records it ("00"), the other sees it already processed ("02")
        List<Object> rspCodes = outcomes.stream().<Object>map(o -> ((Map<?, ?>) o).get("RspCode")).toList();
        assertThat(rspCodes).containsExactlyInAnyOrder("00", "02");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outboxRepository.findAll()).filteredOn(e ->
                e.getAggregateId().equals(String.valueOf(bookingId)) && e.getTopic().equals("payment.completed"))
                .hasSize(1);
    }

    // The IPN VNPay would send for this payment, signed like VNPay does (HMAC-SHA512 of the sorted vnp_* params)
    private static Map<String, String> signedIpn(Payment payment, String responseCode) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TxnRef", payment.getTransactionId());
        params.put("vnp_Amount", String.valueOf(Math.round(payment.getAmount()) * 100));
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", responseCode);
        // VNPay's own transaction number: unique per payment (uk_payments_provider_payment_id), so a new one per run
        params.put("vnp_TransactionNo", String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000, 100_000_000)));
        params.put("vnp_TmnCode", "TESTCODE");

        String data = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(HASH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        params.put("vnp_SecureHash", HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8))));
        return params;
    }
}
