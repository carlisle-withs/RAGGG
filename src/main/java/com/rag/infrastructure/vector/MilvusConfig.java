package com.rag.infrastructure.vector;

import com.rag.config.AppConfig;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    private final AppConfig appConfig;

    public MilvusConfig(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient() {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(appConfig.getMilvus().getUri());

        return new MilvusClientV2(builder.build());
    }
}
