package com.enterprise.seedm.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class SwappableDataSource extends AbstractDataSource {

    private DataSource targetDataSource;

    public SwappableDataSource(DataSource targetDataSource) {
        this.targetDataSource = targetDataSource;
    }

    public void setTargetDataSource(DataSource targetDataSource) {
        // Close old one if it's Hikari
        if (this.targetDataSource instanceof HikariDataSource) {
            ((HikariDataSource) this.targetDataSource).close();
        }
        this.targetDataSource = targetDataSource;
    }
    
    public DataSource getTargetDataSource() {
        return targetDataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return targetDataSource.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return targetDataSource.getConnection(username, password);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return targetDataSource.isWrapperFor(iface);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return targetDataSource.unwrap(iface);
    }
}
