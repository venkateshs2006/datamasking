package com.enterprise.seedm.util;

public class DataTypeConstrants {
    public static final int BYTE_MIN_VALUE=Byte.MIN_VALUE;
    public static final int BYTE_MAX_VALUE=Byte.MAX_VALUE;
    public static final int SHORT_MIN_VALUE=Short.MIN_VALUE;
    public static final int SHORT_MAX_VALUE=Short.MAX_VALUE;
    public static final int INT_MIN_VALUE=Integer.MIN_VALUE;
    public static final int INT_MAX_VALUE=Integer.MAX_VALUE;
    public static final Long LONG_MIN_VALUE=Long.MIN_VALUE;
    public static final Long LONG_MAX_VALUE=Long.MAX_VALUE;
  // Floating Point Types
    public static final double DOUBLE_MIN_VALUE=Double.MIN_VALUE;
    public static final double DOUBLE_MAX_VALUE=Double.MAX_VALUE;
    public static final float FLOAT_MIN_VALUE=Float.MIN_VALUE;
    public static final float FLOAT_MAX_VALUE=Float.MAX_VALUE;
    //Boolean Type
    public static final boolean BOOLEAN_FALSE=false;
    public static final boolean BOOLEAN_TRUE=true;
    //Character Type
    public static final char CHAR_MIN_VALUE=Character.MIN_VALUE;
    public static final char CHAR_MAX_VALUE=Character.MAX_VALUE;

    //Postgres Data types
    public static final int INT2_MIN_VALUE=-32768;
    public static final int INT2_MAX_VALUE=32767;
    public static final int INT4_MIN_VALUE=-2147483648;
    public static final int INT4_MAX_VALUE=2147483647;
    public static final long INT8_MIN_VALUE=-9223372036854775808L;
    public static final long INT8_MAX_VALUE=9223372036854775807L;

    public static final double NUMERIC_MIN_VALUE=-1.e31;
    public static final double NUMERIC_MAX_VALUE=1.e31;

    public static final double MONEY_MIN_VALUE=-922337203685477.5808;
    public static final double MONEY_MAX_VALUE=922337203685477.5807;

    public static final String DATE_MIN_VALUE="1700-01-01";
    public static final String DATE_MAX_VALUE="5899-12-31";

    public static final String TIME_MIN_VALUE="00:00:00";
    public static final String TIME_MAX_VALUE="23:59:59.999999";

    public static final String TIMESTAMP_MIN_VALUE="1700-01-01 00:00:00";
    public static final String TIMESTAMP_MAX_VALUE="5899-12-31 23:59:59.99.99999";

    public static final String INTERVAL_MIN_VALUE="-178 years";
    public static final String INTERVAL_MAX_VALUE="178 years";

    public static final String BOOLEAN_MIN_VALUE="FALSE";
    public static final String BOOLEAN_MAX_VALUE="TRUE";

    public static final String BYTEA_MIN_VALUE="";
    public static final String BYTEA_MAX_VALUE="1GB";

    public static final String TEXT_MIN_VALUE="";
    public static final String TEXT_MAX_VALUE="1GB";

}
