package io.github.sitkowski01.exchange.config;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Oznacza odbiorcow, ktorych silnik dostaje bezposrednio. Bez tego do silnika trafilby kazdy
 * bean typu EventSink -- takze JdbcEventSink, ktory ma byc wolany tylko z watku zapisu.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface EngineSink {
}
