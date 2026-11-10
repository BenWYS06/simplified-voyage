package com.ben.services.service.gateway;

import com.ben.services.model.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The signature is the only thing that proves an IPN really comes from VNPay, so its rules must not drift
class VnPayServiceTest {

    private static final DateTimeFormatter VNPAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VnPayService vnPay = new VnPayService();

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(vnPay, "tmnCode", "TESTCODE");
        ReflectionTestUtils.setField(vnPay, "hashSecret", "test-secret");
        ReflectionTestUtils.setField(vnPay, "payUrl", "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        ReflectionTestUtils.setField(vnPay, "returnUrl", "http://localhost:5173/payment/return");
        ReflectionTestUtils.setField(vnPay, "expireMinutes", 10);
    }

    @Test
    void thePaymentUrlCarriesTheOrderAndASignatureVnPayCanCheck() {
        String url = vnPay.createPaymentUrl(payment(1_500_000.0), "127.0.0.1");
        Map<String, String> params = queryOf(url);

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(params)
                .containsEntry("vnp_Amount", "150000000")
                .containsEntry("vnp_TxnRef", "TXN1")
                .containsEntry("vnp_TmnCode", "TESTCODE")
                .containsEntry("vnp_ReturnUrl", "http://localhost:5173/payment/return/42");
        assertThat(vnPay.isValidSignature(params)).isTrue();
    }

    @Test
    void theLinkExpiresAfterTheConfiguredMinutes() {
        Map<String, String> params = queryOf(vnPay.createPaymentUrl(payment(100.0), "127.0.0.1"));

        LocalDateTime created = LocalDateTime.parse(params.get("vnp_CreateDate"), VNPAY_DATE);
        LocalDateTime expires = LocalDateTime.parse(params.get("vnp_ExpireDate"), VNPAY_DATE);
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aChangedAmountBreaksTheSignature() {
        Map<String, String> params = queryOf(vnPay.createPaymentUrl(payment(100.0), "127.0.0.1"));
        params.put("vnp_Amount", "1");

        assertThat(vnPay.isValidSignature(params)).isFalse();
    }

    @Test
    void aSignatureMadeWithAnotherSecretIsRejected() {
        VnPayService attacker = new VnPayService();
        ReflectionTestUtils.setField(attacker, "tmnCode", "TESTCODE");
        ReflectionTestUtils.setField(attacker, "hashSecret", "guessed-secret");
        ReflectionTestUtils.setField(attacker, "payUrl", "https://x");
        ReflectionTestUtils.setField(attacker, "returnUrl", "http://x");
        ReflectionTestUtils.setField(attacker, "expireMinutes", 10);

        Map<String, String> forged = queryOf(attacker.createPaymentUrl(payment(100.0), "127.0.0.1"));

        assertThat(vnPay.isValidSignature(forged)).isFalse();
    }

    @Test
    void noSignatureIsRejected() {
        Map<String, String> params = queryOf(vnPay.createPaymentUrl(payment(100.0), "127.0.0.1"));
        params.remove("vnp_SecureHash");

        assertThat(vnPay.isValidSignature(params)).isFalse();
    }

    @Test
    void anUppercaseHashAndTheHashTypeParamAreAccepted() {
        Map<String, String> params = queryOf(vnPay.createPaymentUrl(payment(100.0), "127.0.0.1"));
        params.put("vnp_SecureHash", params.get("vnp_SecureHash").toUpperCase());
        params.put("vnp_SecureHashType", "HmacSHA512");

        assertThat(vnPay.isValidSignature(params)).isTrue();
    }

    @Test
    void paramsOutsideVnPaysOwnAreNotSigned() {
        Map<String, String> params = queryOf(vnPay.createPaymentUrl(payment(100.0), "127.0.0.1"));
        // e.g. a query param the frontend adds to the return URL
        params.put("utm_source", "mail");

        assertThat(vnPay.isValidSignature(params)).isTrue();
    }

    @Test
    void amountsAreSentAsWholeVndTimes100() {
        assertThat(vnPay.toVnpAmount(150_000.0)).isEqualTo(15_000_000L);
        assertThat(vnPay.toVnpAmount(99.6)).isEqualTo(10_000L);
    }

    @Test
    void theLinkExpiryCountsFromWhenThePaymentWasCreated() {
        Payment payment = payment(100.0);
        LocalDateTime created = LocalDateTime.of(2026, 12, 25, 8, 0);
        payment.setCreatedAt(created);

        assertThat(vnPay.expiresAt(payment))
                .isEqualTo(created.atZone(ZoneId.systemDefault()).toInstant().plus(Duration.ofMinutes(10)));
    }

    private static Payment payment(Double amount) {
        return Payment.builder().bookingId(42L).amount(amount).transactionId("TXN1").build();
    }

    // Reads the URL back the way VNPay's IPN hands the params to us: decoded values
    private static Map<String, String> queryOf(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.US_ASCII));
        }
        return params;
    }
}
