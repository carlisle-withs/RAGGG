package com.rag.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.rag.config.AppConfig;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    private final AppConfig appConfig;

    public ElasticsearchConfig(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @Bean
    public RestClient restClient() {
        return RestClient.builder(
                new HttpHost(
                        appConfig.getElasticsearch().getHost(),
                        appConfig.getElasticsearch().getPort(),
                        "http"
                )
        ).build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
