package io.github.sitkowski01.exchange.history;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class HistoryConfig {

    @Bean
    TradeHistory tradeHistory(JdbcTemplate jdbc) {
        return new TradeHistory(jdbc);
    }
}
