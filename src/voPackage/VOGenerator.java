package voPackage;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

public class VOGenerator {

    /**
     * this class should be extended at the vo classes for the manipulation field in database
     */
    public VOGenerator() {
        try {
            createTable(getClass());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * This method get the name of table (the name is the substring of name the vo class)
     * es: VOTABLE ---> in database is: TABLE
     *
     * @param clazz value object class
     * @return table name
     */
    private String getTableName(Class<?>clazz){

        String tableName = null;

        if(clazz.isAnnotationPresent(Table.class)){
            Table table = clazz.getAnnotation(Table.class);
            tableName = table.value();

            if(tableName != null && !tableName.isEmpty()){
                System.out.println("table name non null");
            }else {
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
    private List<ColumnBean> getColumns(Class<?>clazz){

        List<ColumnBean> listaColumn = new ArrayList<>();

        Field[] fields = clazz.getDeclaredFields();

        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                ColumnBean columnBean = new ColumnBean();
                Column column = field.getAnnotation(Column.class);

                String columnName = column.name();
                if(columnName == null || columnName.isEmpty()) {
                    columnName = field.getName().toUpperCase();
                }

                String columnType = column.type();
                if(columnType == null || columnType.isEmpty()) {
                    Class<?> typeColumn = field.getType();
                    columnType = typeColumn.getName();
                }

                boolean columnNotNull = column.notNull();

                String columnDefaultValue = column.defaultValue();

                columnBean.setName(columnName);
                columnBean.setType(columnType);
                if(isPkFromDb(columnName)){
                    columnBean.setNotNull(true);
                }else {
                    columnBean.setNotNull(columnNotNull);
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
    private List<String> getPrimaryKey(Class<?> clazz){

        List<String> primaryKey = new ArrayList<>();

        Field[] fieldsID = clazz.getDeclaredFields();
        for (Field field : fieldsID) {
            if(field.isAnnotationPresent(Id.class)){
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
    private String getStrinSQlCreateTable(Class<?> clazz){

        String tableName = getTableName(clazz);
        List<ColumnBean> listaColumns = getColumns(clazz);
        List<String> pkList = getPrimaryKey(clazz);

        StringBuilder builder = new StringBuilder();

        builder.append("CREATE TABLE ");
        builder.append(tableName);
        builder.append(" (");

        for(ColumnBean column : listaColumns){
            boolean isNotNull = column.isNotNull();
            boolean defaultValueExist = column.getDefaultValue() != null && !column.getDefaultValue().isEmpty();

            builder.append(column.getName())
                    .append(" ")
                    .append(getSqlType(column.getType()));
                    if(defaultValueExist){
                        builder.append(" DEFAULT ");
                        if(column.getDefaultValue().matches("^-?[0-9]+$")){
                            builder.append(column.getDefaultValue());
                        }else {
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

        for(int i = 0; i < pkList.size(); i++){
            builder.append(pkList.get(i));
            if(i < pkList.size() - 1) {
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
     * @param con connection to database
     * @param tableName name of table in database
     * @return true if exist
     */
    private boolean existTable(Connection con, String tableName){

        boolean existTable = false;

        try {
            DatabaseMetaData metaData = con.getMetaData();
            ResultSet rs = metaData.getTables(null, null, tableName.toUpperCase(), null);

            if(rs != null && rs.next()){
                existTable = true;

                rs.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return existTable;
    }

    /**
     * This method get the all field form database with all constraints
     *
     * @param con connection to database
     * @param clazz value object class
     * @return list of all column in database
     */
    private List<ColumnBean> getFieldFromDb(Connection con, Class<?> clazz){

        List<ColumnBean> fildsNotPresentInVO = new ArrayList<>();

        String sql = "SELECT * FROM " + getTableName(clazz) + " FETCH FIRST 1 ROW ONLY";

        try {
            Statement st = con.createStatement();
            ResultSet rs = st.executeQuery(sql);

            ResultSet columns = null;

            if(rs != null){
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                DatabaseMetaData dbMetaData = con.getMetaData();
                String tableName = getTableName(clazz);

                for (int i = 1; i <= columnCount; i++) {
                    String nameOfColumn = metaData.getColumnName(i);
                    Object javaType = getJavaObject(metaData.getColumnTypeName(i));

                    ColumnBean fieldBean = new ColumnBean();
                    fieldBean.setName(nameOfColumn);
                    fieldBean.setType(javaType.toString().substring(6));

                    columns = dbMetaData.getColumns(null, null, tableName, nameOfColumn);

                    if(columns != null && columns.next()) {
                        boolean isNullable = "NO".equalsIgnoreCase(columns.getString("IS_NULLABLE"));
                        String defaultValue = columns.getString("COLUMN_DEF");
                        if(defaultValue != null){
                            defaultValue = defaultValue.replace("'","");

                            if("NULL".equals(defaultValue)){
                                defaultValue = "";
                            }
                        }else {
                            defaultValue = "";
                        }

                        fieldBean.setDefaultValue(defaultValue.toUpperCase());
                        fieldBean.setNotNull(isNullable);
                    }

                    fildsNotPresentInVO.add(fieldBean);
                }

                st.close();
                rs.close();

                if (columns != null) {
                    columns.close();
                }
            }

        }catch (SQLException e){
            e.printStackTrace();
        }

        return fildsNotPresentInVO;
    }

    /**
     * This method get all constraints column by column name in databse
     *
      * @param con connection to database
     * @param columnName name of column in database
     * @return the column object
     */
    private ColumnBean getConstraintsColumn(Connection con,String columnName){

        ColumnBean columnBean = new ColumnBean();

        try {
            DatabaseMetaData dbMetaData = con.getMetaData();
            ResultSet rs = dbMetaData.getColumns(null, null, getTableName(getClass()), columnName);

            if(rs != null && rs.next()){
                boolean isNullable = "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String defaultValue = rs.getString("COLUMN_DEF");
                if(defaultValue != null) {
                    defaultValue = defaultValue.replace("'", "");
                    if ("NULL".equals(defaultValue)) {
                        defaultValue = "";
                    }
                }else {
                    defaultValue = "";
                }

                columnBean.setName(columnName);
                columnBean.setNotNull(isNullable);
                columnBean.setDefaultValue(defaultValue);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return columnBean;
    }

    private void createTable(Class<?> clazz) throws SQLException {

        Connection con = doConnection();
        String sql = getStrinSQlCreateTable(clazz);
        String tableName = getTableName(clazz);

        boolean skipDropPk = false;

        try{
            Statement st = con.createStatement();

            if(!existTable(con,tableName)){
                System.out.println("QUERY CREATE TABLE PRE ESEGUITA: " + sql);
                int execute = st.executeUpdate(sql);
                System.out.println("eseguita--> " + execute);

                st.close();

            }else {
                List<ColumnBean> getAllFields = getColumns(clazz);
                List<ColumnBean> getFieldsFromDB = getFieldFromDb(con,clazz);

                if(!getAllFields.isEmpty() && !getFieldsFromDB.isEmpty()) {
                    if (getAllFields.size() < getFieldsFromDB.size()) {

                        for(int i = 0; i < getAllFields.size(); i++) {
                            if (i == 0) {
                                getFieldsFromDB.remove(getFieldsFromDB.get(i));
                            } else {
                                getFieldsFromDB.remove(getFieldsFromDB.get(i - 1));
                            }
                        }

                        String alterDropColumn = getDropColumn(clazz,getFieldsFromDB);

                        //drop primary key nel caso in cui la colonna da togliere è una pk
                        for(int i = 0; i < getFieldsFromDB.size(); i++){
                            String fieldName = getFieldsFromDB.get(i).getName();
                            if(isPkFromDb(fieldName) && !skipDropPk) {
                                String droPk = getDropPk();
                                st.executeUpdate(droPk);
                                skipDropPk = true;
                            }
                        }

                        //drop field
                        if(alterDropColumn != null && !alterDropColumn.isEmpty()){
                            System.out.println(alterDropColumn);
                            st.executeUpdate(alterDropColumn);
                        }

                    } else if (getAllFields.size() > getFieldsFromDB.size()) {

                        for(int i = 0; i < getFieldsFromDB.size(); i++){
                            if(i == 0){
                                getAllFields.remove(getAllFields.get(i));
                            }else {
                                getAllFields.remove(getAllFields.get(i - 1));
                            }

                        }

                        String alterADD = getAlterTableADDStatement(clazz,getAllFields);

                        if(alterADD != null && !alterADD.isEmpty()){
                            System.out.println(alterADD);
                            st.executeUpdate(alterADD);
                        }
                    }

                    getAllFields = getColumns(getClass());
                    getFieldsFromDB = getFieldFromDb(con,getClass());

                    if(!getAllFields.equals(getFieldsFromDB)){
                        for(int i = 0; i < getAllFields.size(); i++){
                            String fieldNameFromVO = getAllFields.get(i).getName();
                            String typeVO = getAllFields.get(i).getType();
                            String columnVODefaultValue = getAllFields.get(i).getDefaultValue();
                            boolean columnVONotNull = getAllFields.get(i).isNotNull();

                            String fieldNameDB = getFieldsFromDB.get(i).getName();
                            String typeDB = getFieldsFromDB.get(i).getType();
                            String columnDBDefaultValue = getFieldsFromDB.get(i).getDefaultValue();
                            boolean columnDBNotNull = getFieldsFromDB.get(i).isNotNull();

                            if(fieldNameDB != null) {
                                //rimuovo il default value se nel vo non è presente ma a db c'è oppure se è diverso fra db è vo
                                if((columnDBDefaultValue.isEmpty() && !columnVODefaultValue.isEmpty()) || columnVODefaultValue.equals(columnDBDefaultValue)) {
                                    String dropDefaultValue = getDropDefaultValue(fieldNameDB, getSqlType(typeDB));
                                    System.out.println(dropDefaultValue);
                                    st.executeUpdate(dropDefaultValue);
                                }

                                //rimuovo il not null value se nel vo non è presente ma a db c'è oppure se è diverso fra db è vo
                                if(fieldNameDB.equals(fieldNameFromVO) && !isPkFromDb(fieldNameDB) && columnDBNotNull && !columnVONotNull){
                                    String sqlDropNotNull = getDropNotNull(fieldNameDB);
                                    System.out.println(sqlDropNotNull);
                                    st.executeUpdate(sqlDropNotNull);
                                }
                            }

                            //cambia il tipo della colonna
                            if(!typeVO.equals(typeDB)){
                                typeVO = getSqlType(typeVO);
                                String modifyType = getModifyTypeStatement(fieldNameDB,typeVO);
                                System.out.println(modifyType);
                                st.executeUpdate(modifyType);
                                typeDB = typeVO;
                            }
                            //cambia il nome della colonna
                            if(!fieldNameFromVO.equals(fieldNameDB)){
                                String modifyName = getModifyNameStatement(fieldNameDB,fieldNameFromVO);
                                System.out.println(modifyName);
                                st.executeUpdate(modifyName);
                                fieldNameDB = fieldNameFromVO;
                            }

                            if (columnVONotNull && !columnDBNotNull) {
                                String sqlAddNotNull = getAddNotNull(fieldNameFromVO);
                                System.out.println(sqlAddNotNull);
                                st.executeUpdate(sqlAddNotNull);

                            }else  if(!columnVONotNull && !isPkFromDb(fieldNameDB) && columnDBNotNull){
                                String sqlDropNotNull = getDropNotNull(fieldNameDB);
                                System.out.println(sqlDropNotNull);
                                st.executeUpdate(sqlDropNotNull);
                            }

                            if (!columnVODefaultValue.isEmpty()) {
                                if (columnVODefaultValue.matches("^-?[0-9]+$") && "NUMBER".equals(getSqlType(typeVO))) {
                                    String addDefaultValue = getAddDefaultValue(fieldNameFromVO, columnVODefaultValue, getSqlType(typeVO));
                                    System.out.println(addDefaultValue);
                                    st.executeUpdate(addDefaultValue);

                                } else if (!"NUMBER".equals(getSqlType(typeVO)) && !columnVODefaultValue.matches("^-?[0-9]+$")) {
                                    String addDefaultValue = getAddDefaultValue(fieldNameFromVO, columnVODefaultValue, getSqlType(typeVO));
                                    System.out.println(addDefaultValue);
                                    st.executeUpdate(addDefaultValue);
                                }
                            }
                        }
                    }else {
                        for(ColumnBean columnVO : getAllFields){
                            String columnVOname = columnVO.getName();
                            String columnVODefaultValue = columnVO.getDefaultValue();
                            boolean columnVONotNull = columnVO.isNotNull();
                            String typeVO = columnVO.getType();

                            ColumnBean columnDB = getConstraintsColumn(con,columnVOname);
                            String columnDBname = columnDB.getName();
                            String columnDBDefaultValue = columnDB.getDefaultValue();
                            boolean columnDBNotNull = columnDB.isNotNull();

                            if((!columnVODefaultValue.isEmpty() && columnDBDefaultValue.isEmpty()) || (!columnVODefaultValue.isEmpty() && !columnVODefaultValue.equals(columnDBDefaultValue))){
                                String addDefaultValue = getAddDefaultValue(columnVOname, columnVODefaultValue, getSqlType(typeVO));
                                st.executeUpdate(addDefaultValue);

                            }else if(!columnDBDefaultValue.isEmpty()){
                                String dropDefaultValue = getDropDefaultValue(columnVOname,getSqlType(typeVO));
                                st.executeUpdate(dropDefaultValue);
                            }

                            if (columnVONotNull && !columnDBNotNull) {
                                String sqlAddNotNull = getAddNotNull(columnVOname);
                                System.out.println(sqlAddNotNull);
                                st.executeUpdate(sqlAddNotNull);

                            }else  if(!columnVONotNull && !isPkFromDb(columnDBname) && columnDBNotNull){
                                String sqlDropNotNull = getDropNotNull(columnDBname);
                                System.out.println(sqlDropNotNull);
                                st.executeUpdate(sqlDropNotNull);
                            }
                        }
                    }

                    if(0 < getFieldsFromDbPk().size() && getPrimaryKey(getClass()).size() > 0){
                        if((getFieldsFromDbPk().size() != getPrimaryKey(getClass()).size()) || !(getPrimaryKey(getClass()).equals(getFieldsFromDbPk()))){
                            String dropPkStatement = getDropPk();
                            st.executeUpdate(dropPkStatement);
                        }
                    }

                    if(0 < getFieldsFromDbPk().size() && getPrimaryKey(getClass()).size() == 0){
                        String dropPkStatement = getDropPk();
                        st.executeUpdate(dropPkStatement);
                    }

                    if(0 == getFieldsFromDbPk().size() && getPrimaryKey(getClass()).size() > 0){
                        String addPkStatement = getAddPK(getPrimaryKey(getClass()));
                        st.executeUpdate(addPkStatement);
                    }

                    st.close();
                    con.commit();

                }else if(getAllFields.isEmpty()){
                    System.out.println("VO" + getTableName(getClass()) + " is empty");
                }
            }

        }catch (SQLException e){
            con.rollback();
            e.printStackTrace();
        }
    }

    private String getDropTable(){

        return "DROP TABLE " + getTableName(getClass());
    }

    private String getDropNotNull(String fieldName){

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                fieldName +
                " NULL ";
    }

    private String getAddNotNull(String fieldName){

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                fieldName +
                " NOT NULL ";
    }

    private String getDropDefaultValue(String fieldName,String columnType){

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                fieldName +
                " " +
                columnType +
                " DEFAULT " +
                "NULL";
    }

    private String getAddDefaultValue(String fieldName,String defaultValue, String columnType){

        StringBuilder builder = new StringBuilder();

        builder.append("ALTER TABLE ")
                .append(getTableName(getClass()))
                .append(" MODIFY ")
                .append(fieldName)
                .append(" ")
                .append(columnType)
                .append(" DEFAULT ");
        if(defaultValue.matches("^-?[0-9]+$")){
            builder.append(defaultValue);
        }else {
            builder.append("'")
                    .append(defaultValue)
                    .append("'");
        }

        return builder.toString();
    }

    private String getAlterTableADDStatement(Class<?> clazz,List<ColumnBean> fieldsList){

        StringBuilder builder = new StringBuilder();

        builder.append("ALTER TABLE ")
                .append(getTableName(clazz))
                .append(" ADD ");

        boolean isSingleField = false;

        if(fieldsList.size() > 1){
            builder.append("(");
        }else {
            isSingleField = true;
        }

        for(int i = 0; i < fieldsList.size(); i++){
            String fieldName = fieldsList.get(i).getName();
            Object fieldType = fieldsList.get(i).getType();
            boolean isNotNull = fieldsList.get(i).isNotNull();
            String defaultValue = fieldsList.get(i).getDefaultValue();

            String oracleFieldType = fieldType.toString().trim();

            builder.append(fieldName)
                    .append(" ")
                    .append(getSqlType(oracleFieldType));
                    if(defaultValue != null && !defaultValue.isEmpty()){
                        builder.append(" DEFAULT ");
                        if(defaultValue.matches("^-?[0-9]+$")){
                            builder.append(defaultValue.toUpperCase());
                        }else {
                            builder.append("'")
                                    .append(defaultValue.toUpperCase())
                                    .append("'");
                        }

                    }
                    if(isNotNull){
                        builder.append(" NOT NULL ");
                    }

            if(i < fieldsList.size() - 1 && !isSingleField){
                builder.append(",");

            }else if(i == fieldsList.size() - 1 && !isSingleField){
                builder.append(")");
            }

        }

        return  builder.toString();
    }

    private String getDropColumn(Class<?> clazz,List<ColumnBean> fieldsList){

        StringBuilder builder = new StringBuilder();

        builder.append("ALTER TABLE ")
                .append(getTableName(clazz))
                .append(" DROP ");

        boolean singleField = false;

        if(fieldsList.size() > 1){
            builder.append("(");

        }else {
            builder.append("COLUMN ");
            singleField = true;
        }

        for(int i = 0; i < fieldsList.size(); i++){
            String fieldName = fieldsList.get(i).getName();

            builder.append(fieldName);

            if(i < fieldsList.size() - 1 && !singleField){
                builder.append(",");

            }else if(i == fieldsList.size() - 1 && !singleField){
                builder.append(")");
            }
        }

        return builder.toString();
    }

    private Connection doConnection(){

        Connection con1 = null;
        Connection con2 = null;

        try {
            InputStream input = VOGenerator.class.getClassLoader().getResourceAsStream("connectionDB.config");
            if(input != null){
                Properties properties = new Properties();
                properties.load(input);

                String driver1 = properties.getProperty("jdbcDriver1");
                String url1 = properties.getProperty("jdbcUrl1");
                String user1 = properties.getProperty("jdbcLogin1");
                String pass1 = properties.getProperty("jdbcPassword1");

                String driver2 = properties.getProperty("jdbcDriver2");
                String url2 = properties.getProperty("jdbcUrl2");
                String user2 = properties.getProperty("jdbcLogin2");
                String pass2 = properties.getProperty("jdbcPassword2");

                String driver3 = properties.getProperty("jdbcDriver3");
                String url3 = properties.getProperty("jdbcUrl3");
                String user3 = properties.getProperty("jdbcLogin3");
                String pass3 = properties.getProperty("jdbcPassword3");

                try {
                    Class.forName(driver3);
                    con1 = DriverManager.getConnection(url3, user3, pass3);
                    con1.setAutoCommit(false);

                }catch (SQLException e){
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("errore nella connessione al database " + e);
        }

        return con1;
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
                    return "VARCHAR2(255)";
                case "java.util.Date":
                case "java.sql.Date":
                case "java.time.LocalDate":
                    return "DATE";
                case "java.sql.Time":
                case "java.time.LocalTime":
                    return "TIMESTAMP";
                case "java.sql.Timestamp":
                case "java.time.LocalDateTime":
                case "java.time.Instant":
                    return "TIMESTAMP";
                case "java.time.OffsetDateTime":
                case "java.time.ZonedDateTime":
                    return "TIMESTAMP WITH TIME ZONE";
                case "java.math.BigDecimal":
                    return "NUMBER"; // Specificare precisione e scala se necessario
                case "java.math.BigInteger":
                    return "NUMBER"; // Specificare precisione se necessario
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
                case "java.util.Calendar":
                case "java.util.GregorianCalendar":
                    return "TIMESTAMP";
                case "java.lang.Enum":
                    return "VARCHAR2(255)";
                case "oracle.sql.BLOB":
                    return "BLOB";
                default:
                    throw new IllegalArgumentException("Tipo Java non supportato: " + javaType);
            }
        }else {
            return javaType;
        }
    }

    private Object getJavaObject(String oracleType) {
        if(!oracleType.contains("VARCHAR")) {
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
                case "TIMESTAMP WITH TIME ZONE":
                    return java.time.OffsetDateTime.class;
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
        }else {
            return java.lang.String.class;
        }
    }

    private boolean isPk(String fieldName){

        boolean isPk = false;

        Field[] fields = getClass().getDeclaredFields();

        for(Field field : fields){
            field.setAccessible(true);
            if(fieldName.equalsIgnoreCase(field.getName())){
                if(field.isAnnotationPresent(Id.class)){
                    isPk = true;
                    break;
                }
            }
        }

        return isPk;
    }

    private boolean isPkFromDb(String fieldNameDb){

        boolean isPk = false;

        try{
            DatabaseMetaData metaData = doConnection().getMetaData();

            ResultSet rs = metaData.getPrimaryKeys(null,null,getTableName(getClass()));

            while (rs != null && rs.next()){
                String columnName = rs.getString("COLUMN_NAME");

                if(fieldNameDb != null && !fieldNameDb.isEmpty()){
                    if(columnName != null && columnName.equalsIgnoreCase(fieldNameDb)){
                        isPk = true;
                    }
                }
            }


        }catch (SQLException e){
            e.printStackTrace();
        }


        return isPk;
    }

    private String getDropPk(){

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " " +
                "DROP CONSTRAINT " +
                "pk_" + getTableName(getClass());
    }

    private String getAddPK(List<String> pklist){

        StringBuilder builder = new StringBuilder();

        if(pklist != null) {
            builder.append("ALTER TABLE ")
                    .append(getTableName(getClass()))
                    .append(" ADD CONSTRAINT ")
                    .append("pk_")
                    .append(getTableName(getClass()))
                    .append(" PRIMARY KEY ")
                    .append("(");

            for(int i = 0; i < pklist.size(); i++){
                builder.append(pklist.get(i));
                if(i < pklist.size() - 1){
                    builder.append(",");
                }
            }
            builder.append(")");
        }

        return builder.toString();
    }

    private String getFieldType(String typeClass){

        String returnType = null;

        if(typeClass != null && !typeClass.isEmpty()){
            if(typeClass.contains(".")){
                returnType = typeClass.substring(6);
            }else {
                returnType = typeClass;
            }
        }

        return returnType;
    }

    private List<String> getFieldsFromDbPk(){

        List<ColumnBean> getFieldsFromDB = getFieldFromDb(doConnection(),getClass());
        List<String> fieldsFromDbName = new ArrayList<>();

        for(ColumnBean bean : getFieldsFromDB){
            if(isPkFromDb(bean.getName())){
                fieldsFromDbName.add(bean.getName().toUpperCase());
            }
        }

        return fieldsFromDbName;
    }

    private String getDefaultValueVOByColumnName(String columnName) {

        String defaultValue = null;

        Field[] fields = getClass().getDeclaredFields();

        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                String fieldName = field.getAnnotation(Column.class).name();

                if(columnName.equalsIgnoreCase(fieldName)){
                    if(field.getAnnotation(Column.class).defaultValue() != null && !field.getAnnotation(Column.class).defaultValue().isEmpty()){
                        defaultValue = field.getAnnotation(Column.class).defaultValue();
                    }
                }
            }
        }
        return defaultValue;
    }

    private String getModifyTypeStatement(String columnName, String newType){

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " MODIFY " +
                "(" +
                columnName +
                " " +
                newType +
                ")";
    }

    private String getModifyNameStatement(String oldName, String newName){

        return "ALTER TABLE " +
                getTableName(getClass()) +
                " RENAME COLUMN " +
                oldName +
                " TO " +
                newName;
    }
}
