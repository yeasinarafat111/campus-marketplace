package com.campus.marketplace.config;

import com.cloudinary.Cloudinary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class CloudinaryConfig {

    @Bean
    public Cloudinary cloudinary(CloudinaryProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("Cloudinary credentials are not set (CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / "
                    + "CLOUDINARY_API_SECRET); image uploads will be rejected with 503");
        }
        Map<String, Object> config = new HashMap<>();
        config.put("cloud_name", properties.cloudName());
        config.put("api_key", properties.apiKey());
        config.put("api_secret", properties.apiSecret());
        config.put("secure", true);
        return new Cloudinary(config);
    }
}
