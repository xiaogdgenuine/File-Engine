package file.engine.services.utils.connection;

import file.engine.configs.AllConfigs;
import file.engine.configs.Constants;
import file.engine.event.handler.EventManagement;
import file.engine.event.handler.impl.stop.RestartEvent;
import file.engine.utils.ThreadPoolUtil;
import file.engine.utils.RegexUtil;
import file.engine.utils.file.FileUtil;
import file.engine.utils.system.properties.IsDebug;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteOpenMode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * @author XUANXU
 */
public class SQLiteUtil {
    private static final SQLiteConfig sqLiteConfig = new SQLiteConfig();
    private static final ConcurrentHashMap<String, ConnectionWrapper> connectionPool = new ConcurrentHashMap<>();
    private static String currentDatabaseDir = "data";

    static {
        Consumer<ConnectionWrapper> checkConnectionAndClose = (conn) -> {
            if (conn.isIdleTimeout()) {
                try {
                    conn.lock.lock();
                    if (conn.isIdleTimeout() && !conn.connection.isClosed()) {
                        if (IsDebug.isDebug()) {
                            System.out.println("长时间未使用 " + conn.url + "  已关闭连接");
                        }
                        conn.connection.close();
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                } finally {
                    conn.lock.unlock();
                }
            }
        };
        ThreadPoolUtil threadPoolUtil = ThreadPoolUtil.getInstance();
        threadPoolUtil.executeTask(() -> {
            long checkTimeMills = 0;
            final long threshold = 10_000; // 10s
            try {
                while (!threadPoolUtil.isShutdown()) {
                    if (System.currentTimeMillis() - checkTimeMills > threshold) {
                        checkTimeMills = System.currentTimeMillis();
                        for (ConnectionWrapper conn : connectionPool.values()) {
                            checkConnectionAndClose.accept(conn);
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private static void initSqliteConfig() {
        sqLiteConfig.setTempStore(SQLiteConfig.TempStore.FILE);
        sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqLiteConfig.setOpenMode(SQLiteOpenMode.NOMUTEX);
        sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
        sqLiteConfig.setLockingMode(SQLiteConfig.LockingMode.NORMAL);
    }

    public static void openAllConnection() {
        ThreadPoolUtil.getInstance().executeTask(() -> {
            for (ConnectionWrapper conn : connectionPool.values()) {
                try {
                    conn.lock.lock();
                    if (conn.connection.isClosed()) {
                        conn.connection = DriverManager.getConnection(conn.url, sqLiteConfig.toProperties());
                    }
                    conn.usingTimeMills = System.currentTimeMillis();
                } catch (SQLException e) {
                    e.printStackTrace();
                } finally {
                    conn.lock.unlock();
                }
            }
        });
    }

    private static ConnectionWrapper getFromConnectionPool(String key) throws SQLException {
        ConnectionWrapper connectionWrapper = connectionPool.get(key);
        if (connectionWrapper == null) {
            throw new IllegalArgumentException("no connection named " + key);
        }
        try {
            connectionWrapper.lock.lock();
            if (connectionWrapper.connection.isClosed()) {
                connectionWrapper.connection = DriverManager.getConnection(connectionWrapper.url, sqLiteConfig.toProperties());
                System.out.println("已恢复连接 " + connectionWrapper.url);
            }
            connectionWrapper.usingTimeMills = System.currentTimeMillis();
        } finally {
            connectionWrapper.lock.unlock();
        }
        return connectionWrapper;
    }

    /**
     * 不要用于大量数据的select查询，否则可能会占用大量内存
     *
     * @param sql select语句
     * @return 已编译的PreparedStatement
     * @throws SQLException 失败
     */
    public static PreparedStatement getPreparedStatement(String sql, String key) throws SQLException {
        if (isConnectionNotInitialized(key)) {
            File data = new File(currentDatabaseDir, key + ".db");
            initConnection("jdbc:sqlite:" + data.getAbsolutePath(), key);
        }
        ConnectionWrapper connectionWrapper = getFromConnectionPool(key);
        return new PreparedStatementWrapper((SQLiteConnection) connectionWrapper.connection, sql, connectionWrapper.connectionUsingCounter);
    }

    /**
     * 用于需要重复运行多次指令的地方
     *
     * @return Statement
     * @throws SQLException 失败
     */
    public static Statement getStatement(String key) throws SQLException {
        if (isConnectionNotInitialized(key)) {
            File data = new File(currentDatabaseDir, key + ".db");
            initConnection("jdbc:sqlite:" + data.getAbsolutePath(), key);
        }
        ConnectionWrapper wrapper = getFromConnectionPool(key);
        return new StatementWrapper((SQLiteConnection) wrapper.connection, wrapper.connectionUsingCounter);
    }

    private static boolean isConnectionNotInitialized(String key) {
        if (connectionPool.isEmpty()) {
            throw new RuntimeException("The connection must be initialized first, call initConnection(String url)");
        }
        return !connectionPool.containsKey(key);
    }

    public static void initConnection(String url, String key) throws SQLException {
        initSqliteConfig();
        ConnectionWrapper connectionWrapper = new ConnectionWrapper(url);
        connectionPool.put(key, connectionWrapper);
    }

    /**
     * 关闭所有数据库连接
     */
    public static void closeAll() {
        if (IsDebug.isDebug()) {
            System.err.println("正在关闭数据库连接");
        }
        final int timeout = 30_000; // 30s
        for (var entry : connectionPool.entrySet()) {
            ConnectionWrapper v = entry.getValue();
            try {
                v.lock.lock();
                final long checkTime = System.currentTimeMillis();
                while (v.isConnectionUsing() && System.currentTimeMillis() - checkTime > timeout) {
                    TimeUnit.MILLISECONDS.sleep(50);
                }
                v.connection.close();
            } catch (SQLException | InterruptedException e) {
                e.printStackTrace();
            } finally {
                v.lock.unlock();
            }
        }
        connectionPool.clear();
    }

    private static void deleteMalFormedFile() {
        Path malformedDB = Path.of("user/malformedDB");
        if (Files.exists(malformedDB)) {
            String line;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("user/malformedDB"), StandardCharsets.UTF_8))) {
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.isBlank()) {
                        continue;
                    }
                    Files.delete(Path.of(line));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                Files.delete(malformedDB);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String initializeAndGetDiskPath() {
        Path data = Path.of("data");
        if (!Files.exists(data)) {
            try {
                Files.createDirectories(data);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        String disks = AllConfigs.getInstance().getAvailableDisks();
        if (disks == null || disks.isEmpty() || disks.isBlank()) {
            throw new RuntimeException("initialize failed");
        }
        return disks;
    }

    /**
     * 复制数据库文件到另一个文件夹
     *
     * @param fromDir 源路径
     * @param toDir   目标路径
     */
    public static void copyDatabases(String fromDir, String toDir) {
        File cache = new File(fromDir, "cache.db");
        FileUtil.copyFile(cache, new File(toDir, "cache.db"));
        File weight = new File(fromDir, "weight.db");
        FileUtil.copyFile(weight, new File(toDir, "weight.db"));
        String[] split = RegexUtil.comma.split(initializeAndGetDiskPath());
        for (String eachDisk : split) {
            String dbName = eachDisk.charAt(0) + ".db";
            File data = new File(fromDir, dbName);
            FileUtil.copyFile(data, new File(toDir, dbName));
        }
    }

    /**
     * 检查数据库中表是否为空
     *
     * @param tableNames 所有待检测的表名
     * @return true如果超过10个表结果都不超过10条
     */
    private static boolean isDatabaseEmpty(ArrayList<String> tableNames) throws SQLException {
        int emptyNum = 0;
        for (String each : RegexUtil.comma.split(AllConfigs.getInstance().getAvailableDisks())) {
            try (Statement stmt = SQLiteUtil.getStatement(String.valueOf(each.charAt(0)))) {
                for (String tableName : tableNames) {
                    String sql = String.format("SELECT ASCII FROM %s LIMIT 10;", tableName);
                    try (ResultSet resultSet = stmt.executeQuery(sql)) {
                        int count = 0;
                        while (resultSet.next()) {
                            count++;
                        }
                        if (count < 10) {
                            emptyNum++;
                        }
                    }
                }
            }
        }
        return emptyNum > 10;
    }

    /**
     * 检查数据库是否损坏
     *
     * @return boolean
     */
    public static boolean isDatabaseDamaged() {
        try {
            ArrayList<String> list = new ArrayList<>();
            for (int i = 0; i <= Constants.ALL_TABLE_NUM; i++) {
                list.add("list" + i);
            }
            return isDatabaseEmpty(list);
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
    }

    public static void initAllConnections() {
        initAllConnections("data");
    }

    public static void initAllConnections(String dir) {
        currentDatabaseDir = dir;
        deleteMalFormedFile();
        String[] split = RegexUtil.comma.split(initializeAndGetDiskPath());
        ArrayList<File> malformedFiles = new ArrayList<>();
        for (String eachDisk : split) {
            File data = new File(dir, eachDisk.charAt(0) + ".db");
            try {
                initConnection("jdbc:sqlite:" + data.getAbsolutePath(), String.valueOf(eachDisk.charAt(0)));
                initTables(String.valueOf(eachDisk.charAt(0)));
            } catch (Exception e) {
                malformedFiles.add(data);
            }
        }

        File cache = new File(dir, "cache.db");
        try {
            initConnection("jdbc:sqlite:" + cache.getAbsolutePath(), "cache");
            createCacheTable();
            createPriorityTable();
        } catch (SQLException e) {
            malformedFiles.add(cache);
        }
        File weight = new File(dir, "weight.db");
        try {
            initConnection("jdbc:sqlite:" + weight.getAbsolutePath(), "weight");
            createWeightTable();
        } catch (SQLException exception) {
            malformedFiles.add(weight);
        }
        if (!malformedFiles.isEmpty()) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("user/malformedDB"), StandardCharsets.UTF_8))) {
                for (File file : malformedFiles) {
                    writer.write(file.getAbsolutePath());
                    writer.newLine();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            EventManagement.getInstance().putEvent(new RestartEvent());
        }
    }

    /**
     * 检查表是否存在
     *
     * @return true or false
     */
    @SuppressWarnings("SameParameterValue")
    private static boolean isTableExist(String tableName, String key) {
        try (Statement p = getStatement(key)) {
            p.execute(String.format("SELECT * FROM %s", tableName));
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private static void createWeightTable() throws SQLException {
        try (PreparedStatement pStmt = getPreparedStatement("CREATE TABLE IF NOT EXISTS weight(TABLE_NAME text unique, TABLE_WEIGHT INT)", "weight")) {
            pStmt.executeUpdate();
        }
        try (Statement stmt = getStatement("weight")) {
            for (int i = 0; i < 41; i++) {
                String tableName = "list" + i;
                String format = String.format("INSERT OR IGNORE INTO weight values(\"%s\", %d)", tableName, 0);
                stmt.executeUpdate(format);
            }
        }
    }

    /**
     * 初始化表
     *
     * @param disk disk
     */
    private static void initTables(String disk) {
        try (Statement stmt = getStatement(disk)) {
            for (int i = 0; i < 41; i++) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS list" + i + "(ASCII INT, PATH TEXT, PRIORITY INT, PRIMARY KEY(\"ASCII\",\"PATH\",\"PRIORITY\"))");
            }
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }

    private static void createPriorityTable() throws SQLException {
        if (isTableExist("priority", "cache")) {
            return;
        }
        try (Statement statement = getStatement("cache")) {
            int row = statement.executeUpdate("CREATE TABLE IF NOT EXISTS priority(SUFFIX text unique, PRIORITY INT)");
            if (row == 0) {
                int count = 10;
                HashMap<String, Integer> map = new HashMap<>();
                map.put("lnk", count--);
                map.put("exe", count--);
                map.put("bat", count--);
                map.put("cmd", count--);
                map.put("txt", count--);
                map.put("docx", count--);
                map.put("zip", count--);
                map.put("rar", count--);
                map.put("7z", count--);
                map.put("html", count);
                map.put("defaultPriority", 0);
                insertAllSuffixPriority(map, statement);
            }
        }
    }

    private static void createCacheTable() throws SQLException {
        try (PreparedStatement pStmt = getPreparedStatement("CREATE TABLE IF NOT EXISTS cache(PATH text unique);", "cache")) {
            pStmt.executeUpdate();
        }
    }

    private static void insertAllSuffixPriority(HashMap<String, Integer> suffixMap, Statement statement) {
        try {
            statement.execute("BEGIN;");
            suffixMap.forEach((suffix, priority) -> {
                String generateFormattedSql = generateFormattedSql(suffix, priority);
                try {
                    statement.execute(generateFormattedSql);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            });
        } catch (SQLException throwable) {
            throwable.printStackTrace();
        } finally {
            try {
                statement.execute("COMMIT;");
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private static String generateFormattedSql(String suffix, int priority) {
        return String.format("INSERT OR IGNORE INTO priority VALUES(\"%s\", %d)", suffix, priority);
    }

    private static class ConnectionWrapper {
        private final String url;
        private Connection connection;
        private volatile long usingTimeMills;
        private final AtomicInteger connectionUsingCounter = new AtomicInteger();
        private final ReentrantLock lock = new ReentrantLock();
        private final int randomTimeMills;
        private static final Random random = new Random();

        private ConnectionWrapper(String url) throws SQLException {
            this.url = url;
            this.connection = DriverManager.getConnection(url, sqLiteConfig.toProperties());
            this.usingTimeMills = System.currentTimeMillis();
            this.randomTimeMills = random.nextInt(9000) + 1000; //随机添加超时时间，从1秒到10秒，防止所有连接同时关闭
        }

        private boolean isIdleTimeout() {
            return System.currentTimeMillis() - this.usingTimeMills > Constants.CLOSE_DATABASE_TIMEOUT_MILLS + this.randomTimeMills && connectionUsingCounter.get() == 0;
        }

        private boolean isConnectionUsing() {
            return connectionUsingCounter.get() != 0;
        }
    }
}
