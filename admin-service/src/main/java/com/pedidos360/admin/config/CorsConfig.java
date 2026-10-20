package com.pedidos360.admin.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Permisos de origen cruzado para el panel de mensajeria del frontend.
 *
 * <p>El panel corre en el navegador, en un puerto distinto al de este servicio,
 * asi que sin esto el navegador bloquearia cada llamada. Los origenes
 * permitidos estan en configuracion y no en el codigo, para poder agregar uno
 * sin recompilar.
 *
 * <p>No se usa comodin: se enumeran los origenes. Un comodin aqui dejaria que
 * cualquier pagina abierta en el navegador operara la mensajeria.
 */
@Configuration
@ConfigurationProperties(prefix = "pedido360.cors")
public class CorsConfig implements WebMvcConfigurer {

    private List<String> origenesPermitidos = List.of();

    public List<String> getOrigenesPermitidos() {
        return origenesPermitidos;
    }

    public void setOrigenesPermitidos(List<String> origenesPermitidos) {
        this.origenesPermitidos = origenesPermitidos;
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        registro.addMapping("/admin/**")
                .allowedOrigins(origenesPermitidos.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
