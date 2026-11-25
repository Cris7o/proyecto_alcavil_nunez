package com.pedidos360.streaming.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.pedidos360.streaming.comun.CalendarioNegocio;

/**
 * CORS para el panel del frontend y beans de apoyo del dominio.
 *
 * <p>Los origenes se enumeran en configuracion; no se usa comodin.
 */
@Configuration
@ConfigurationProperties(prefix = "pedido360.cors")
public class WebConfig implements WebMvcConfigurer {

    private List<String> origenesPermitidos = List.of();

    public List<String> getOrigenesPermitidos() {
        return origenesPermitidos;
    }

    public void setOrigenesPermitidos(List<String> origenesPermitidos) {
        this.origenesPermitidos = origenesPermitidos;
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        registro.addMapping("/**")
                .allowedOrigins(origenesPermitidos.toArray(String[]::new))
                .allowedMethods("GET", "PUT", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Bean
    public CalendarioNegocio calendarioNegocio(StreamingProperties propiedades) {
        return new CalendarioNegocio(propiedades.getZonaHoraria());
    }
}
