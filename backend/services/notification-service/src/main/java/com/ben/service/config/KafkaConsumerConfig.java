package com.ben.service.config;

import com.ben.common_lib.event.BookingConfirmedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, BookingConfirmedEvent> bookingConfirmedConsumerFactory() {
        JsonDeserializer<BookingConfirmedEvent> deserializer =
                new JsonDeserializer<>(BookingConfirmedEvent.class);
        deserializer.setRemoveTypeHeaders(false);
        deserializer.addTrustedPackages("com.ben.common_lib.event");
        deserializer.setUseTypeMapperForKey(true);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-service-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // For resilience: don't commit until message is fully processed
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // ErrorHandlingDeserializer: a message that can't be parsed reaches the error handler (→ DLT)
        // instead of failing the poll forever
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(deserializer));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BookingConfirmedEvent>
    bookingConfirmedKafkaListenerContainerFactory(DefaultErrorHandler kafkaErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, BookingConfirmedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bookingConfirmedConsumerFactory());
        factory.setConcurrency(3);  // 3 parallel consumer threads

        // Was: 2 retries, then the message was dropped. Now: retry with backoff, then DLT (never drop)
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    // Producer used only to publish failed messages to <topic>.DLT (notification sends nothing else)
    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * A failing message (mail server down, bug) is retried with growing waits: 1 s, 2 s, 4 s ... capped at 1 min,
     * 10 retries ≈ 5 min, then published to booking.confirmed.DLT instead of being dropped.
     * Messages that can never be read (bad JSON) skip the retries and go straight to the DLT.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> dltTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(10);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(60_000);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(dltTemplate), backOff);
    }
}
