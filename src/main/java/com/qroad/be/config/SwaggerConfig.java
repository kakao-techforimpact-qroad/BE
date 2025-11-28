package com.qroad.be.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        Info info = new Info()
                .title("QRoad API 명세서")       // 문서 제목
                .description("QRoad 백엔드 REST API 문서입니다.") // 설명
                .version("v1.0.0");

        Server localServer = new Server();
        localServer.setDescription("서버");

        return new OpenAPI()
                .info(info)
                .servers(List.of(localServer));
    }
}
