import daoPackage.DAOGenerator;
import voPackage.VOGenerator;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class Main {

    public static void main(String[] args) {

        Connection con = doConnection();

        VOD02RT001 vo = new VOD02RT001();
        //setting value with getter and setter

        DAOGenerator dao = new DAOGenerator(con);
        dao.delete(vo);
        dao.create(vo);
        dao.readByPrimaryKey(vo);
        dao.update(vo);
    }

    public static Connection doConnection(){

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
}
