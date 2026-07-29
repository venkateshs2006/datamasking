package coms.seedm.model;

import java.util.List;

public class TableMetadata {

    private final String schema;
    private final String tableName;
    private final List<ColumnMetadata> columns;

    public TableMetadata(String schema, String tableName, List<ColumnMetadata> columns) {
        this.schema = schema;
        this.tableName = tableName;
        this.columns = columns;
    }

    public String getSchema() {
        return schema;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }

    public String qualifiedName() {
        return schema + "." + tableName;
    }

    public ColumnMetadata column(String name) {
        return columns.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
