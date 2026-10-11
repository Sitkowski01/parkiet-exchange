package io.github.sitkowski01.exchange.api;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Opis API w OpenAPI 3: {@code /v3/api-docs} (JSON, np. do wygenerowania klienta) i Swagger UI pod
 * {@code /swagger-ui.html}, gdzie kazdy endpoint mozna wywolac z przegladarki. Sciezki, pola i walidacja
 * ida z kontrolerow i rekordow -- opis nie rozjedzie sie z kodem.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static {
        // Ceny ida w JSON-ie jako tekst ("250.40", @JsonFormat STRING) -- w opisie tez, nie jako liczba.
        SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class, new StringSchema().example("250.40"));
    }

    @Bean
    OpenAPI parkietApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Parkiet — silnik giełdy")
                        .version("1")
                        .description("""
                                Zlecenia, arkusz i świece. Ceny w złotówkach jako tekst ("250.40"), w środku \
                                w groszach. Błędy w formacie RFC 9457 (application/problem+json). Notowania na \
                                żywo idą WebSocketem: ws://host/ws/market?symbols=CDR,PKO.""")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .externalDocs(new ExternalDocumentation().description("README")
                        .url("https://github.com/Sitkowski01/parkiet-exchange#readme"));
    }
}
