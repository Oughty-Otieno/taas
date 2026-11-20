package com.taas.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * API documentation: the OpenAPI document at /v3/api-docs and Swagger UI at /swagger-ui.html (also the root
 * page). Use Authorize with a token from POST /v1/dev/token.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI taasOpenApi() {
        return new OpenAPI()
                .info(new Info().title("TaaS: Tokenisation as a Service").version("v1").description("""
                        One platform for loyalty points and fractional real-world assets.

                        **Getting a token (local/demo):** call `POST /v1/dev/token` with body `{}` (all scopes) or
                        `{"clientId":"my-app","scopes":["token:read","token:award"]}`, copy `access_token`, click
                        **Authorize** and paste it.

                        **Writes** need an `Idempotency-Key` header: the same key returns the stored reply
                        (`Idempotent-Replay: true`) and never runs the workflow twice; the same key with a different
                        body is a 409.

                        **Balances** are derived from the token ledger's movements; nothing here edits a balance.
                        Rejections answer `{status, code, detail, actionId}` and are recorded in the Action Ledger."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    @RestController
    static class Root {
        @GetMapping("/")
        RedirectView root() {
            return new RedirectView("/swagger-ui.html");
        }
    }
}
