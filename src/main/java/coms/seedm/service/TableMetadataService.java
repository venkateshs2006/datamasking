package coms.seedm.service;

import com.seedm.model.ColumnMetadata;
import com.seedm.model.TableMetadata;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class TableMetadataService {

    public TableMetadata readMetadata(DataSource dataSource, String schema, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            List<ColumnMetadata> columns = new ArrayList<>();

            try (ResultSet rs = meta.getColumns(null, schema, tableName, null)) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    int dataType = rs.getInt("DATA_TYPE");
                    String typeName = rs.getString("TYPE_NAME");
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    columns.add(new ColumnMetadata(name, dataType, typeName, nullable));
                }
            }

            if (columns.isEmpty()) {
                throw new IllegalStateException(
                        "No columns found for table '" + schema + "." + tableName +
                        "'. Check that the table exists and the DB user has access.");
            }

            return new TableMetadata(schema, tableName, columns);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to read metadata for table '" + schema + "." + tableName + "'", e);
        }
    }
}
