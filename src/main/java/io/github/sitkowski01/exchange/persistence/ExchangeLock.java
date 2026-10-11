package io.github.sitkowski01.exchange.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Jedna gielda na baze. Dwie instancje na jednym dzienniku wznowilyby ten sam przebieg i pisaly te same
 * numery zdarzen -- {@code on conflict do nothing} po cichu zgubilby zdarzenia jednej z nich.
 *
 * <p>Blokada doradcza PostgreSQL ({@code pg_try_advisory_lock}) na osobnym polaczeniu, trzymanym przez caly
 * czas pracy: baza zwalnia ja sama, gdy polaczenie zniknie, takze po kill -9.
 */
public final class ExchangeLock implements AutoCloseable {

    /** Dowolna, stala liczba: "PARKIET" w ASCII. */
    static final long KEY = 0x5041524B494554L;

    private final Connection connection;

    private ExchangeLock(Connection connection) {
        this.connection = connection;
    }

    /** @throws IllegalStateException gdy baze trzyma juz inna gielda -- wtedy ta nie wystartuje */
    public static ExchangeLock acquire(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select pg_try_advisory_lock(" + KEY + ")")) {
            result.next();
            if (!result.getBoolean(1)) {
                throw new IllegalStateException("another exchange instance is already using this database");
            }
            return new ExchangeLock(connection);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** Zwalnia blokade -- po zapisaniu calego dziennika (Spring zamyka ja po odbiorcach zdarzen). */
    @Override
    public void close() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("select pg_advisory_unlock(" + KEY + ")");
        } finally {
            connection.close();
        }
    }
}
