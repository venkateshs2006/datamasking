package coms.seedm.model;

import java.sql.Types;

public class ColumnMetadata {

    private final String name;
    private final int jdbcType;
    private final String typeName;
    private final boolean nullable;

    public ColumnMetadata(String name, int jdbcType, String typeName, boolean nullable) {
        this.name = name;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.nullable = nullable;
    }

    public String getName() {
        return name;
    }

    public int getJdbcType() {
        return jdbcType;
    }

    public String getTypeName() {
        return typeName;
    }

    public boolean isNullable() {
        return nullable;
    }

    public boolean isNumeric() {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> true;
            default -> false;
        };
    }

    public boolean isDateOrTime() {
        return switch (jdbcType) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP,
                 Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    public boolean isBoolean() {
        return jdbcType == Types.BOOLEAN || jdbcType == Types.BIT;
    }

    public boolean isCharacter() {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                 Types.NVARCHAR, Types.LONGNVARCHAR -> true;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return name + "(" + typeName + ")";
    }
}
