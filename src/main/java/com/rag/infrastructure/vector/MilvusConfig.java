package com.rag.infrastructure.vector;

import com.rag.config.AppConfig;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    private final AppConfig appConfig;

    public MilvusConfig(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @Bean
    public MilvusServiceClient milvusClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(appConfig.getMilvus().getHost())
                .withPort(appConfig.getMilvus().getPort())
                .build();
        return new MilvusServiceClient(connectParam);
    }
}
