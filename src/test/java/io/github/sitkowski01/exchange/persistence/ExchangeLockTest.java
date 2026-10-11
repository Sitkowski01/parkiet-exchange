package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Druga gielda na tej samej bazie nie wystartuje, dopoki pierwsza nie zwolni blokady. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExchangeLockTest {

    @Autowired
    DataSource dataSource;

    @Test
    void secondInstanceIsRefusedUntilTheFirstLetsGo() throws Exception {
        ExchangeLock first = ExchangeLock.acquire(dataSource);

        assertThatThrownBy(() -> ExchangeLock.acquire(dataSource))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("another exchange instance");

        first.close();
        ExchangeLock.acquire(dataSource).close();
    }
}
