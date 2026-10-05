package com.campus.marketplace.config;

import com.campus.marketplace.service.FeeCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps {@link FeeCalculator} free of Spring: it is built here from the validated properties. */
@Configuration
public class FeeConfig {

    @Bean
    public FeeCalculator feeCalculator(PaymentProperties properties) {
        PaymentProperties.Fee fee = properties.fee();
        return new FeeCalculator(fee.threshold(), fee.lowRate(), fee.highRate(), fee.minimumCents());
    }
}
