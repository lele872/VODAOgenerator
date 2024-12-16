package voPackage;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetProvider;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VOGenerator {

    protected static final Logger logger = Logger.getLogger(VOGenerator.class.getName());

    /**
     * this class should be extended at the vo classes for the manipulation field in database
     */
    public VOGenerator() {
        Connection con = null;
        try {
            //con = DataSourceUtil.getInstance().getConnection();
            con = getConnection();
            if (con != null) {
                con.setAutoCommit(false);
                createTable(getClass(), con);
                con.commit();
            }
        } catch (Exception e) {
            try {
                if (con != null) {
                    con.rollback();
                }
            } catch (SQLException ex) {
                logger.info("info IN ROLLBACK TRANSACTIONS " + ex.getMessage());
            }
            throw new RuntimeException(e.getMessage());

        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (SQLException e) {
                logger.info("info IN CLOSING CONNECTION " + e.getMessage());
            }
        }
    }

    /**
     * This method get the name of table (the name is the substring of name the vo class)
     * es: VOTABLE ---> in database is: TABLE
     *
     * @param clazz value object class
     * @return table name
     */
    private String getTableName(Class<?> clazz) {

        String tableName = null;

        if (clazz.isAnnotationPresent(Entity.class)) {
            Entity entity = clazz.getAnnotation(Entity.class);
            tableName = entity.name();

            if (tableName == null || tableName.isEmpty()) {
                tableName = clazz.getSimpleName().substring(2);
            }
        }
        return tableName;
    }

    /**
     * This method get the list of @interface Column field
     *
     * @param clazz value object class
     * @return list column field annotation
     */
    private List<ColumnBean> getColumns(Class<?> clazz, Connection con) {

        List<ColumnBean> listaColumn = new ArrayList<>();

        Field[] fields = clazz.getDeclaredFields();

        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                ColumnBean columnBean = new ColumnBean();
                Column column = field.getAnnotation(Column.class);

                String columnName = column.name();
                if (columnName == null || columnName.isEmpty()) {
                    columnName = field.getName().toUpperCase();
                }

                String javaTypeField = field.getType().getName();
                String oracleCtrl = replaceOracleTypeWithoutLength(getSqlType(javaTypeField));

                String columnType = column.type();
                if (columnType == null || columnType.isEmpty()) {
                    Class<?> typeColumn = field.getType();
                    columnType = typeColumn.getName();
                    columnType = getSqlType(columnType);
                }

                if (!columnType.contains(oracleCtrl)) {
                    throw new RuntimeException("the type of attribute in vo: " + columnName + " miss matched to oracle type");
                }

                boolean columnNotNull = column.notNull();

                String columnDefaultValue = column.defaultValue();

                columnBean.setName(columnName);
                columnBean.setType(columnType);
                try {
                    if (isPkFromDb(columnName, con)) {
                        columnBean.setNotNull(true);
                    } else {
                        columnBean.setNotNull(columnNotNull);
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e.getMessage());
                }
                columnBean.setDefaultValue(columnDefaultValue);

                listaColumn.add(columnBean);
            }
        }

        return listaColumn;
    }

    /**
     * This method check the annotation id is present on vo field and get this
     *
     * @param clazz value object class
     * @return list of primary key annotated in vo
     */
    private List<String> getPrimaryKey(Class<?> clazz) {

        List<String> primaryKey = new ArrayList<>();

        Field[] fieldsID = clazz.getDeclaredFields();
        for (Field field : fieldsID) {
            if (field.isAnnotationPresent(Id.class)) {
                String namePK = field.getName();
                primaryKey.add(namePK.toUpperCase());
            }
        }

        return primaryKey;
    }

    /**
     * This method make the dynamically create table query
     *
     * @param clazz value object class
     * @return create table query
     */
    private String getStrinSQlCreateTable(Class<?> clazz, Connection con) {

        String tableName = getTableName(clazz);
        List<ColumnBean> listaColumns = getColumns(clazz, con);
        List<String> pkList = getPrimaryKey(clazz);

        StringBuilder builder = new StringBuilder();

        builder.append("CREATE TABLE ");
        builder.append(tableName);
        builder.append(" (");

        for (ColumnBean column : listaColumns) {
            boolean isNotNull = column.isNotNull();
            boolean defaultValueExist = column.getDefaultValue() != null && !column.getDefaultValue().isEmpty();

            builder.append(column.getName())
                    .append(" ")
                    .append(column.getType());
            if (defaultValueExist) {
                builder.append(" DEFAULT ");
                if (column.getDefaultValue().matches("^-?[0-9]+$")) {
                    builder.append(column.getDefaultValue());
                } else {
                    builder.append("'")
                            .append(column.getDefaultValue())
                            .append("'");
                }
            }
            builder.append(isNotNull ? " NOT NULL " : "")
                    .append(",");
        }

        builder.append(" CONSTRAINT ").append('\"').append("PK_").append(tableName).append("\"");
        builder.append(" PRIMARY KEY (");

        for (int i = 0; i < pkList.size(); i++) {
            builder.append(pkList.get(i));
            if (i < pkList.size() - 1) {
                builder.append(",");
            }
        }
        builder.append(")");
        builder.append(")");

        return builder.toString();
    }

    /**
     * This method check if exist table in databse
     *
     * @param con       connection to database
     * @param tableName name of table in database
     * @return true if exist
     */
    private boolean existTable(Connection con, String tableName) throws SQLException {

        boolean existTable = false;
        ResultSet rs = null;
        try {
            DatabaseMetaData metaData = con.getMetaData();
            rs = metaData.getTables(null, null, tableName.toUpperCase(), null);

            if (rs != null && rs.next()) {
                existTable = true;
            }
        } catch (SQLException e) {
            throw new SQLException("info IN CHECKING TABLE IF EXISTS " + e.getMessage());

        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
            } catch (SQLException e) {
                logger.info("info IN CLOSING RESOURCES " + e.getMessage());
            }
        }

        return existTable;
    }

    /**
     * This method get the all field form database with all constraints
     *
     * @param con   connection to database
     * @param clazz value object class
     * @return list of all column in database
     */
    private List<ColumnBean> getFieldFromDb(Connection con, Class<?> clazz) throws SQLException {

        List<ColumnBean> fieldsNotPresentInVO = new ArrayList<>();

        String sql = "SELECT * FROM " + getTableName(clazz) + " FETCH FIRST 1 ROW ONLY";

        Statement st = null;
        ResultSet rs = null;

        ResultSet columns = null;

        try {
            st = con.createStatement();
            rs = st.executeQuery(sql);

            if (rs != null) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                DatabaseMetaData dbMetaData = con.getMetaData();
                String tableName = getTableName(clazz);

                for (int i = 1; i <= columnCount; i++) {
                    ColumnBean fieldBean = new ColumnBean();

                    String nameOfColumn = metaData.getColumnName(i);
                    int typeSize = metaData.getPrecision(i);

                    String type = "";
                    if (typeSize == 0) {
                        type = metaData.getColumnTypeName(i);
                    } else {
                        type = metaData.getColumnTypeName(i) + "(" + typeSize + ")";
                    }

                    fieldBean.setName(nameOfColumn);
                    fieldBean.setType(type);

                    columns = dbMetaData.getColumns(null, null, tableName, nameOfColumn);

                    CachedRowSet cachedRowSet = RowSetProvider.newFactory().createCachedRowSet();
                    cachedRowSet.populate(columns);

                    if (cachedRowSet.next()) {
                        boolean isNullable = "NO".equalsIgnoreCase(cachedRowSet.getString("IS_NULLABLE"));
                        String defaultValue = cachedRowSet.getString("COLUMN_DEF");
                        if (defaultValue != null) {
                            defaultValue = defaultValue.replace("'", "");

                            if ("NULL".equals(defaultValue)) {
                                defaultValue = "";
                            }
                        } else {
                            defaultValue = "";
                        }

                        fieldBean.setDefaultValue(defaultValue.toUpperCase());
                        fieldBean.setNotNull(isNullable);

                        cachedRowSet.close();
                    }

                    fieldsNotPresentInVO.add(fieldBean);
                }
            }
        } catch (SQLException e) {
            throw new SQLException("info IN GETTING METADATA FORM DB: " + e.getMessage());

        } finally {
            try {
                if (rs != null) {
                    rs.close();
                    st.close();
                }
                if (columns != null) {
                    columns.close();
                }
            } catch (SQLException e) {
                logger.info("info IN CLOSING RESOURCES");
            }
        }

        return fieldsNotPresentInVO;
    }

    /**
     * This method get all constraints column by column name in databse
     *
     * @param con        connection to database
     * @param columnName name of column in database
     * @return the column object
     */
    private ColumnBean getConstraintsColumn(Connection con, String columnName) throws SQLException {

        ColumnBean columnBean = new ColumnBean();

        try {
            DatabaseMetaData dbMetaData = con.getMetaData();
            ResultSet rs = dbMetaData.getColumns(null, null, getTableName(getClass()), columnName);

            if (rs != null && rs.next()) {
                boolean isNullable = "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String defaultValue = rs.getString("COLUMN_DEF");
                if (defaultValue != null) {
                    defaultValue = defaultValue.replace("'", "");
                    if ("NULL".equals(defaultValue)) {
                        defaultValue = "";
                    }
                } else {
                    defaultValue = "";
                }

                columnBean.setName(columnName);
                columnBean.setNotNull(isNullable);
                columnBean.setDefaultValue(defaultValue);
            }

        } catch (SQLException e) {
            throw new SQLException("info GETTING CONSTRAINT FOR COLUMN: " + columnName);
        }

        return columnBean;
    }

    private void createTable(Class<?> clazz, Connection con) throws SQLException {

        String tableName = getTableName(clazz);

        boolean skipDropPk = false;
        boolean callingDB = false;
        boolean isAddColumn = false;
        boolean isDropColumn = false;

        Statement st = con.createStatement();

        try {
            if (!existTable(con, tableName)) {
                String sql = getStrinSQlCreateTable(clazz, con);
                logger.info("CREATING TABLE " + tableName + ": " + sql);
                st.executeUpdate(sql);

            } else {
                List<ColumnBean> getAllFields = getColumns(clazz, con);
                List<ColumnBean> getFieldsFromDB = getFieldFromDb(con, clazz);

                if (!getAllFields.isEmpty() && !getFieldsFromDB.isEmpty()) {
                    if (getAllFields.size() < getFieldsFromDB.size()) {
                        isDropColumn = true;
                        /*for (int i = 0; i < getAllFields.size(); i++) {
                            if (i == 0) {
                                getFieldsFromDB.remove(getFieldsFromDB.get(i));
                            } else {
                                getFieldsFromDB.remove(getFieldsFromDB.get(0));
                            }
                        }*/

                        getFieldsFromDB.removeAll(getAllFields);

                        //drop primary key nel caso in cui la colonna da togliere è una pk
                        for (int i = 0; i < getFieldsFromDB.size(); i++) {
                            String fieldName = getFieldsFromDB.get(i).getName();
                            if (isPkFromDb(fieldName, con) && !skipDropPk) {
                                String droPk = getDropPk();
                                logger.info("DROPPING PRIMARY KEY/s: " + droPk);
                                st.executeUpdate(droPk);
                                skipDropPk = true;
                            }
                        }

                    } else if (getAllFields.size() > getFieldsFromDB.size()) {
                        isAddColumn = true;
                        /*for (int i = 0; i < getFieldsFromDB.size(); i++) {
                            if (i == 0) {
                                getAllFields.remove(getAllFields.get(i));
                            } else {
                                getAllFields.remove(getAllFields.get(0));
                            }
                        }*/

                        getAllFields.removeAll(getFieldsFromDB);
                    }

                    /*if(callingDB) {
                        getAllFields = getColumns(clazz, con);
                        getFieldsFromDB = getFieldFromDb(con, clazz);
                    }*/

                    if (getAllFields.size() == getFieldsFromDB.size() /*&& !getAllFields.equals(getFieldsFromDB)*/) {
                        for (int i = 0; i < getAllFields.size(); i++) {
                            String fieldNameFromVO = getAllFields.get(i).getName();
                            String typeVO = getAllFields.get(i).getType();
                            String columnVODefaultValue = getAllFields.get(i).getDefaultValue();
                            boolean columnVONotNull = getAllFields.get(i).isNotNull();

                            String fieldNameDB = getFieldsFromDB.get(i).getName();
                            String typeDB = getFieldsFromDB.get(i).getType();
                            String columnDBDefaultValue = getFieldsFromDB.get(i).getDefaultValue();
                            boolean columnDBNotNull = getFieldsFromDB.get(i).isNotNull();

                            if (fieldNameDB != null) {
                                //rimuovo il not null value se nel vo non è presente ma a db c'è oppure se è diverso fra db è vo (lo rimuovo a db)
                                if (fieldNameDB.equals(fieldNameFromVO) && !isPkFromDb(fieldNameDB, con) && columnDBNotNull && !columnVONotNull) {
                                    String sqlDropNotNull = getDropNotNull(fieldNameDB);
                                    logger.info("DROPPING NOT NULL CONSTRAINT: " + sqlDropNotNull);
                                    st.executeUpdate(sqlDropNotNull);
                                    columnDBNotNull = false;
                                }
                            }

                            //cambia il tipo della colonna
                            if (!typeVO.equals(typeDB)) {
                                String modifyType = getModifyTypeStatement(fieldNameDB, typeVO);
                                logger.info("MODIFYING TYPE STATEMENT: " + modifyType);
                                st.executeUpdate(modifyType);
                            }
                            //cambia il nome della colonna
                            if (!fieldNameFromVO.equals(fieldNameDB)) {
                                String modifyName = getModifyNameStatement(fieldNameDB, fieldNameFromVO);
                                logger.info("MODIFYING NAME STATEMENT: " + modifyName);
                                st.executeUpdate(modifyName);
                                fieldNameDB = fieldNameFromVO;
                            }

                            if (columnVONotNull && !columnDBNotNull) {
                                String sqlAddNotNull = getAddNotNull(fieldNameFromVO);
                                logger.info("ADDING NOT NULL CONSTRAINT: " + sqlAddNotNull);
                                st.executeUpdate(sqlAddNotNull);

                                String defaultValueIfIsNotNull = null;
                                if(!typeVO.contains("NUMBER")){
                                    defaultValueIfIsNotNull = "";
                                }else {
                                    defaultValueIfIsNotNull = "0";
                                }
                                String addDefaultValue = getAddDefaultValue(fieldNameFromVO, defaultValueIfIsNotNull, typeVO);
                                logger.info("ADDING DEFAULT VALUE CONSTRAINT: " + addDefaultValue);
                                st.executeUpdate(addDefaultValue);

                            } else if (!columnVONotNull && !isPkFromDb(fieldNameDB, con) && !columnVODefaultValue.isEmpty() && columnDBNotNull) {
                                String sqlDropNotNull = getDropNotNull(fieldNameDB);
                                logger.info("DROPPING NOT NULL CONSTRAINT: " + sqlDropNotNull);
                                st.executeUpdate(sqlDropNotNull);
                            }

                            if ((!columnVODefaultValue.isEmpty()) && !columnVODefaultValue.equals(columnDBDefaultValue)) {
                                if (typeVO.contains("NUMBER") && !columnVODefaultValue.matches("^-?[0-9]+$")) {
                                    throw new SQLException("cannot adding default value: " + columnVODefaultValue + " for " + fieldNameFromVO + " because the type is " + typeVO);

                                }else {
                                    String addDefaultValue = getAddDefaultValue(fieldNameFromVO, columnVODefaultValue, typeVO);
                                    logger.info("ADDING DEFAULT VALUE CONSTRAINT: " + addDefaultValue);
                                    st.executeUpdate(addDefaultValue);
                                }
                                //TODO sistemare il fatto che parte sempre la query che mette default null sempre
                            } else if(!isPk(fieldNameFromVO) && columnVODefaultValue.isEmpty() && !columnDBNotNull && !columnDBDefaultValue.isEmpty()){
                                String dropDefaultValue = getDropDefaultValue(fieldNameDB, typeDB);
                                logger.info("DROPPING DEFAULT VALUE CONSTRAINT: " + dropDefaultValue);
                                st.executeUpdate(dropDefaultValue);
                            }else if(isPk(fieldNameFromVO)){
                                String defaultValueIfIsNotNull = null;
                                if(!typeVO.contains("NUMBER")){
                                    defaultValueIfIsNotNull = "";
                                }else {
                                    defaultValueIfIsNotNull = "0";
                                }
                                String addDefaultValue = getAddDefaultValue(fieldNameFromVO, defaultValueIfIsNotNull, typeVO);
                                logger.info("ADDING DEFAULT VALUE CONSTRAINT: " + addDefaultValue);
                                st.executeUpdate(addDefaultValue);
                            }
                        }
                    }

                    if (isAddColumn) {
                        String alterADD = getAlterTableADDStatement(clazz, getAllFields);
                        if (alterADD != null && !alterADD.isEmpty()) {
                            logger.info("ADDING COLUMN/s: " + alterADD);
                            st.executeUpdate(alterADD);
                        }
                    }

                    if (isDropColumn) {
                        String alterDropColumn = getDropColumn(clazz, getFieldsFromDB);
                        //drop field
                        if (alterDropColumn != null && !alterDropColumn.isEmpty()) {
                            logger.info("DROPPING COLUMN/s: " + alterDropColumn);
                            st.executeUpdate(alterDropColumn);
                        }
                    }

                    List<String> fieldsFromDbPk = getFieldsFromDbPk(con, getFieldsFromDB);
                    List<String> primaryKeyVO = getPrimaryKey(getClass());

                    if (0 < fieldsFromDbPk.size() && primaryKeyVO.size() > 0) {
                        if ((fieldsFromDbPk.size() != primaryKeyVO.size()) || !(primaryKeyVO.equals(fieldsFromDbPk))) {
                            String dropPkStatement = getDropPk();
                            logger.info("DROPPING PRIMARY KEY: " + dropPkStatement);
                            st.executeUpdate(dropPkStatement);
                        }
                    }

                    if (0 < fieldsFromDbPk.size() && primaryKeyVO.size() == 0) {
                        String dropPkStatement = getDropPk();
                        logger.info("DROPPING PRIMARY KEY: " + dropPkStatement);
                        st.executeUpdate(dropPkStatement);
                    }

                    if (0 == fieldsFromDbPk.size() && primaryKeyVO.size() > 0) {
                        String addPkStatement = getAddPK(getPrimaryKey(getClass()));
                        logger.info("ADDING PRIMARY KEY: " + addPkStatement);
                        st.executeUpdate(addPkStatement);
                    }

                } else if (getAllFields.isEmpty()) {
                    logger.info("VO: " + getTableName(getClass()) + " HASN'T ATTRIBUTES");
                }
            }

        } catch (SQLException e) {
            throw new SQLException("info CREATING OR UPDATING VO: " + getTableName(getClass()) + " EXCEPTION IS: " + e.getMessage());

        } finally {
            st.close();
        }
    }

    private String getDropTable() {

        return "DROP TABLE " + getTableName(getClass());
    }

    private String getDropNotNull(String fieldName) {

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                fieldName +
                " NULL ";
    }

    private String getAddNotNull(String fieldName) {

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                fieldName +
                " NOT NULL ";
    }

    private String getDropDefaultValue(String fieldName, String columnType) {

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                fieldName +
                " " +
                columnType +
                " DEFAULT " +
                "NULL";
    }

    private String getAddDefaultValue(String fieldName, String defaultValue, String columnType) {

        StringBuilder builder = new StringBuilder();

        builder.append("ALTER TABLE ")
                .append(getTableName(getClass()))
                .append(" MODIFY ")
                .append(fieldName)
                .append(" ")
                .append(columnType)
                .append(" DEFAULT ");
        if (defaultValue.matches("^-?[0-9]+$")) {
            builder.append(defaultValue);
        } else {
            builder.append("'")
                    .append(defaultValue)
                    .append("'");
        }

        return builder.toString();
    }

    private String getAlterTableADDStatement(Class<?> clazz, List<ColumnBean> fieldsList) {

        StringBuilder builder = new StringBuilder();

        builder.append("ALTER TABLE ")
                .append(getTableName(clazz))
                .append(" ADD ");

        boolean isSingleField = false;

        if (fieldsList.size() > 1) {
            builder.append("(");
        } else {
            isSingleField = true;
        }

        for (int i = 0; i < fieldsList.size(); i++) {
            String fieldName = fieldsList.get(i).getName();
            Object fieldType = fieldsList.get(i).getType();
            boolean isNotNull = fieldsList.get(i).isNotNull();
            String defaultValue = fieldsList.get(i).getDefaultValue();

            String oracleFieldType = fieldType.toString().trim();

            builder.append(fieldName)
                    .append(" ")
                    .append(oracleFieldType);
            if (defaultValue != null && !defaultValue.isEmpty()) {
                builder.append(" DEFAULT ");
                if (defaultValue.matches("^-?[0-9]+$")) {
                    builder.append(defaultValue.toUpperCase());
                } else {
                    builder.append("'")
                            .append(defaultValue.toUpperCase())
                            .append("'");
                }

            }
            if (isNotNull) {
                builder.append(" NOT NULL ");
            }

            if (i < fieldsList.size() - 1 && !isSingleField) {
                builder.append(",");

            } else if (i == fieldsList.size() - 1 && !isSingleField) {
                builder.append(")");
            }

        }

        return builder.toString();
    }

    private String getDropColumn(Class<?> clazz, List<ColumnBean> fieldsList) {

        StringBuilder builder = new StringBuilder();

        builder.append("ALTER TABLE ")
                .append(getTableName(clazz))
                .append(" DROP ");

        boolean singleField = false;

        if (fieldsList.size() > 1) {
            builder.append("(");

        } else {
            builder.append("COLUMN ");
            singleField = true;
        }

        for (int i = 0; i < fieldsList.size(); i++) {
            String fieldName = fieldsList.get(i).getName();

            builder.append(fieldName);

            if (i < fieldsList.size() - 1 && !singleField) {
                builder.append(",");

            } else if (i == fieldsList.size() - 1 && !singleField) {
                builder.append(")");
            }
        }

        return builder.toString();
    }

    private String getSqlType(String javaType) {
        if (javaType.contains(".")
                || javaType.contains("byte")
                || javaType.contains("short")
                || javaType.contains("int")
                || javaType.contains("long")
                || javaType.contains("float")
                || javaType.contains("double")
                || javaType.contains("boolean")
                || javaType.contains("char")
                || javaType.contains("byte[]")) {
            switch (javaType) {
                case "byte":
                case "java.lang.Byte":
                    return "NUMBER(3)";
                case "short":
                case "java.lang.Short":
                    return "NUMBER(5)";
                case "int":
                case "java.lang.Integer":
                    return "NUMBER(10)";
                case "long":
                case "java.lang.Long":
                    return "NUMBER(19)";
                case "float":
                case "java.lang.Float":
                    return "BINARY_FLOAT";
                case "double":
                case "java.lang.Double":
                    return "BINARY_DOUBLE";
                case "boolean":
                case "java.lang.Boolean":
                    return "NUMBER(1)";
                case "char":
                case "java.lang.Character":
                    return "CHAR(1)";
                case "java.lang.String":
                case "java.lang.Enum":
                    return "VARCHAR2(255)";
                case "java.util.Date":
                case "java.sql.Date":
                case "java.time.LocalDate":
                    return "DATE";
                case "java.sql.Time":
                case "java.time.LocalTime":
                case "java.util.Calendar":
                case "java.util.GregorianCalendar":
                case "java.sql.Timestamp":
                case "java.time.LocalDateTime":
                case "java.time.Instant":
                    return "TIMESTAMP";
                case "java.time.OffsetDateTime":
                case "java.time.ZonedDateTime":
                    return "TIMESTAMP WITH TIME ZONE";
                case "java.math.BigInteger":
                case "java.math.BigDecimal":
                    return "NUMBER";
                case "byte[]":
                    return "RAW(2000)";
                case "java.util.UUID":
                    return "RAW(16)";
                case "java.net.URL":
                    return "VARCHAR2(2000)";
                case "java.util.Currency":
                    return "CHAR(3)";
                case "java.util.Locale":
                    return "VARCHAR2(20)";
                case "java.util.TimeZone":
                    return "VARCHAR2(50)";
                case "oracle.sql.BLOB":
                    return "BLOB";
                default:
                    throw new IllegalArgumentException("Tipo Java non supportato: " + javaType);
            }
        } else {
            return javaType;
        }
    }

    private Object getJavaObject(String oracleType) {
        if (!oracleType.contains("VARCHAR")) {
            switch (oracleType) {
                case "NUMBER(3)":
                    return Byte.class; // Restituisce il tipo di classe
                case "NUMBER(5)":
                    return Short.class;
                case "NUMBER(10)":
                    return Integer.class;
                case "NUMBER(19)":
                    return Long.class;
                case "BINARY_FLOAT":
                    return Float.class;
                case "BINARY_DOUBLE":
                    return Double.class;
                case "NUMBER(1)":
                    return Boolean.class;
                case "CHAR(1)":
                    return Character.class;
                case "DATE":
                    return java.util.Date.class;
                case "TIMESTAMP":
                    return java.sql.Timestamp.class;
                case "RAW(2000)":
                    return byte[].class;
                case "RAW(16)":
                    return java.util.UUID.class;
                case "NUMBER":
                    return java.math.BigDecimal.class;
                case "BLOB":
                    return oracle.sql.BLOB.class;
                default:
                    throw new IllegalArgumentException("Tipo Oracle non supportato: " + oracleType);
            }
        } else {
            return java.lang.String.class;
        }
    }

    private boolean isPk(String fieldName) {

        boolean isPk = false;

        Field[] fields = getClass().getDeclaredFields();

        for (Field field : fields) {
            field.setAccessible(true);
            if (fieldName.equalsIgnoreCase(field.getName())) {
                if (field.isAnnotationPresent(Id.class)) {
                    isPk = true;
                    break;
                }
            }
        }

        return isPk;
    }

    private boolean isPkFromDb(String fieldNameDb, Connection con) throws SQLException {

        boolean isPk = false;
        ResultSet rs = null;

        try {
            DatabaseMetaData metaData = con.getMetaData();

            rs = metaData.getPrimaryKeys(null, null, getTableName(getClass()));

            while (rs != null && rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");

                if (fieldNameDb != null && !fieldNameDb.isEmpty()) {
                    if (columnName != null && columnName.equalsIgnoreCase(fieldNameDb)) {
                        isPk = true;
                    }
                }
            }
        } catch (SQLException e) {
            throw new SQLException("info DURING CHECK IF " + fieldNameDb + " IS A PRIMARY KEY: " + e.getMessage());

        } finally {
            if (rs != null) {
                rs.close();
            }
        }

        return isPk;
    }

    private String getDropPk() {

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " " +
                "DROP CONSTRAINT " +
                "pk_" + getTableName(getClass());
    }

    private String getAddPK(List<String> pklist) {

        StringBuilder builder = new StringBuilder();

        if (pklist != null) {
            builder.append("ALTER TABLE ")
                    .append(getTableName(getClass()))
                    .append(" ADD CONSTRAINT ")
                    .append("pk_")
                    .append(getTableName(getClass()))
                    .append(" PRIMARY KEY ")
                    .append("(");

            for (int i = 0; i < pklist.size(); i++) {
                builder.append(pklist.get(i));
                if (i < pklist.size() - 1) {
                    builder.append(",");
                }
            }
            builder.append(")");
        }

        return builder.toString();
    }

    private String getFieldType(String typeClass) {

        String returnType = null;

        if (typeClass != null && !typeClass.isEmpty()) {
            if (typeClass.contains(".")) {
                returnType = typeClass.substring(6);
            } else {
                returnType = typeClass;
            }
        }

        return returnType;
    }

    private List<String> getFieldsFromDbPk(Connection con, List<ColumnBean> getFieldsFromDB) {

        List<String> fieldsFromDbName = new ArrayList<>();

        try {
            //List<ColumnBean> getFieldsFromDB = getFieldFromDb(con, getClass());

            for (ColumnBean bean : getFieldsFromDB) {
                if (isPkFromDb(bean.getName(), con)) {
                    fieldsFromDbName.add(bean.getName().toUpperCase());
                }

            }
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage());
        }

        return fieldsFromDbName;
    }

    private String getDefaultValueVOByColumnName(String columnName) {

        String defaultValue = null;

        Field[] fields = getClass().getDeclaredFields();

        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                String fieldName = field.getAnnotation(Column.class).name();

                if (columnName.equalsIgnoreCase(fieldName)) {
                    if (field.getAnnotation(Column.class).defaultValue() != null && !field.getAnnotation(Column.class).defaultValue().isEmpty()) {
                        defaultValue = field.getAnnotation(Column.class).defaultValue();
                    }
                }
            }
        }
        return defaultValue;
    }

    private String getModifyTypeStatement(String columnName, String newType) {

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                "(" +
                columnName +
                " " +
                newType +
                ")";
    }

    private String getModifyNameStatement(String oldName, String newName) {

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " RENAME COLUMN " +
                oldName +
                " TO " +
                newName;
    }


    private boolean isPresentTypeAnnotation(String fieldName) {

        boolean isPresent = false;

        Field[] fields = getClass().getDeclaredFields();

        for (Field field : fields) {
            if (fieldName.equals(field.getName())) {
                Column column = field.getAnnotation(Column.class);
                if (column.type().isEmpty()) {
                    isPresent = true;
                    break;
                }
            }

        }
        return isPresent;
    }

    private String getLengthTypeOracle(String oracleType) {

        String regex = ".*\\((\\d+)\\).*";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(oracleType);

        if (matcher.matches()) {
            oracleType = matcher.group(1);
        }

        return oracleType;
    }

    private String replaceOracleTypeWithoutLength(String oracleType) {

        if (oracleType.contains("(")) {
            int indexOpenTonda = oracleType.indexOf("(");
            oracleType = oracleType.substring(0, indexOpenTonda);
        }

        return oracleType;
    }

    private Connection getConnection() throws SQLException {

        return DriverManager.getConnection("jdbc:oracle:thin:@10.11.0.11:1521:oracle19", "SNBC_NCBC", "SNBC_NCBC");
    }
}
