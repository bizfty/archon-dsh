package com.example.dsh;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Bean
    public OpenAPI dshOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DSH Anchon API")
                        .version("0.0.1")
                        .description("DSH (DeepSeek Harness) Java 复刻 — Spring Boot 4 + Spring AI 2.0 多模块工程。"
                                + "提供会话管理、Agent Loop、工具管线、SSE/OpenAI 兼容等 API。")
                        .contact(new Contact()
                                .name("Anchon Team")
                                .email("anchon@example.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .addServersItem(new Server()
                        .url("http://localhost:" + serverPort)
                        .description("开发服务器"));
    }
}