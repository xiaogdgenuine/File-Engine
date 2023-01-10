package file.engine.services;

import com.google.gson.Gson;
import file.engine.annotation.EventListener;
import file.engine.annotation.EventRegister;
import file.engine.configs.AllConfigs;
import file.engine.configs.Constants;
import file.engine.dllInterface.FileMonitor;
import file.engine.dllInterface.GetAscII;
import file.engine.dllInterface.GetHandle;
import file.engine.dllInterface.GetWindowsKnownFolder;
import file.engine.dllInterface.gpu.GPUAccelerator;
import file.engine.event.handler.Event;
import file.engine.event.handler.EventManagement;
import file.engine.event.handler.impl.BootSystemEvent;
import file.engine.event.handler.impl.configs.SetConfigsEvent;
import file.engine.event.handler.impl.database.*;
import file.engine.event.handler.impl.database.gpu.GPUAddRecordEvent;
import file.engine.event.handler.impl.database.gpu.GPUClearCacheEvent;
import file.engine.event.handler.impl.database.gpu.GPURemoveRecordEvent;
import file.engine.event.handler.impl.frame.searchBar.SearchBarReadyEvent;
import file.engine.event.handler.impl.monitor.disk.StartMonitorDiskEvent;
import file.engine.event.handler.impl.stop.RestartEvent;
import file.engine.event.handler.impl.taskbar.ShowTaskBarMessageEvent;
import file.engine.services.utils.PathMatchUtil;
import file.engine.services.utils.SystemInfoUtil;
import file.engine.services.utils.connection.SQLiteUtil;
import file.engine.utils.Bit;
import file.engine.utils.CachedThreadPoolUtil;
import file.engine.utils.ProcessUtil;
import file.engine.utils.RegexUtil;
import file.engine.utils.file.FileUtil;
import file.engine.utils.gson.GsonUtil;
import file.engine.utils.system.properties.IsDebug;
import lombok.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;

public class DatabaseService {
    private static boolean isEnableGPUAccelerate = false;
    // 搜索任务队列
    private static final ConcurrentLinkedQueue<SearchTask> searchTasksQueue = new ConcurrentLinkedQueue<>();
    // 预搜索任务map，当发送PrepareSearchEvent后，将会创建预搜索任务，并放入该map中。
    // 发送StartSearchEvent后将会先寻找预搜索任务，成功找到则直接添加进入searchTasksQueue中，不重新创建搜索任务。
    private static final ConcurrentHashMap<SearchInfo, SearchTask> prepareTasksMap = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SQLWithTaskId> sqlCommandQueue = new ConcurrentLinkedQueue<>();
    //保存每个key所对应的结果数量，数量为0的则直接跳过搜索，不执行SQL查找数据库
    private final ConcurrentHashMap<String, AtomicInteger> databaseResultsCount = new ConcurrentHashMap<>();
    private final AtomicReference<Constants.Enums.DatabaseStatus> status = new AtomicReference<>(Constants.Enums.DatabaseStatus.NORMAL);
    private final AtomicBoolean isExecuteSqlImmediately = new AtomicBoolean(false);
    // 保存从0-40数据库的表，使用频率和名字对应，使经常使用的表最快被搜索到
    private final Set<TableNameWeightInfo> tableSet = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isDatabaseUpdated = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<SuffixPriorityPair> priorityMap = new ConcurrentLinkedQueue<>();
    //tableCache 数据表缓存，在初始化时将会放入所有的key和一个空的cache，后续需要缓存直接放入空的cache中，不再创建新的cache实例
    private final ConcurrentHashMap<String, Cache> tableCache = new ConcurrentHashMap<>();
    private final AtomicInteger tableCacheCount = new AtomicInteger();
    // 对数据库cache表的缓存，保存常用的应用
    private final ConcurrentSkipListSet<String> databaseCacheSet = new ConcurrentSkipListSet<>();
    private final AtomicInteger searchThreadCount = new AtomicInteger(0);
    private long startSearchTimeMills = 0;
    private static final int MAX_TEMP_QUERY_RESULT_CACHE = 128;
    private static final int MAX_CACHED_RECORD_NUM = 10240 * 5;
    private static final int MAX_SQL_NUM = 5000;
    private static final int MAX_RESULTS = 200;

    private static volatile DatabaseService INSTANCE = null;

    private DatabaseService() {
    }

    public static DatabaseService getInstance() {
        if (INSTANCE == null) {
            synchronized (DatabaseService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DatabaseService();
                }
            }
        }
        return INSTANCE;
    }

    private void prepareSearchTasks(SearchTask searchTask) {
        //每个priority用一个线程，每一个后缀名对应一个优先级
        //按照优先级排列，key是sql和表名的对应，value是容器
        var nonFormattedSql = getNonFormattedSqlFromTableQueue(searchTask.searchInfo);
        //添加搜索任务到队列
        addSearchTasks(nonFormattedSql, searchTask);
    }

    private void invalidateAllCache() {
        GPUClearCacheEvent gpuClearCacheEvent = new GPUClearCacheEvent();
        EventManagement eventManagement = EventManagement.getInstance();
        eventManagement.putEvent(gpuClearCacheEvent);
        eventManagement.waitForEvent(gpuClearCacheEvent, 60_000);
        tableCache.values().forEach(each -> {
            each.isCached.set(false);
            each.data = null;
        });
        tableCacheCount.set(0);
    }

    /**
     * 通过表名获得表的权重信息
     *
     * @param tableName 表名
     * @return 权重信息
     */
    private TableNameWeightInfo getInfoByName(String tableName) {
        for (TableNameWeightInfo each : tableSet) {
            if (each.tableName.equals(tableName)) {
                return each;
            }
        }
        return null;
    }

    /**
     * 更新权重信息
     *
     * @param tableName 表名
     * @param weight    权重
     */
    private void updateTableWeight(String tableName, long weight) {
        TableNameWeightInfo origin = getInfoByName(tableName);
        if (origin == null) {
            return;
        }
        origin.weight.addAndGet(weight);
        String format = String.format("UPDATE weight SET TABLE_WEIGHT=%d WHERE TABLE_NAME=\"%s\"", origin.weight.get(), tableName);
        addToCommandQueue(new SQLWithTaskId(format, SqlTaskIds.UPDATE_WEIGHT, "weight"));
        if (IsDebug.isDebug()) {
            System.err.println("已更新" + tableName + "权重, 之前为" + origin + "***增加了" + weight);
        }
    }

    /**
     * 处理所有sql线程
     */
    private void executeSqlCommandsThread() {
        CachedThreadPoolUtil.getInstance().executeTask(() -> {
            EventManagement eventManagement = EventManagement.getInstance();
            while (eventManagement.notMainExit()) {
                if (isExecuteSqlImmediately.get()) {
                    try {
                        isExecuteSqlImmediately.set(false);
                        executeAllCommands();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(20);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * 开始监控磁盘文件变化
     */
    private static void startMonitorDisk() {
        CachedThreadPoolUtil cachedThreadPoolUtil = CachedThreadPoolUtil.getInstance();
        EventManagement eventManagement = EventManagement.getInstance();
        TranslateService translateService = TranslateService.getInstance();
        String disks = AllConfigs.getInstance().getAvailableDisks();
        String[] splitDisks = RegexUtil.comma.split(disks);
        if (isAdmin()) {
            for (String root : splitDisks) {
                cachedThreadPoolUtil.executeTask(() -> FileMonitor.INSTANCE.monitor(root), false);
            }
        } else {
            eventManagement.putEvent(new ShowTaskBarMessageEvent(
                    translateService.getTranslation("Warning"),
                    translateService.getTranslation("Not administrator, file monitoring function is turned off")));
        }
    }

    private void searchFolder(String folder, SearchTask searchTask) {
        if (!Files.exists(Path.of(folder))) {
            return;
        }
        File path = new File(folder);
        if (searchTask.shouldStopSearch.get()) {
            return;
        }
        File[] files = path.listFiles();
        if (null == files || files.length == 0) {
            return;
        }
        List<File> filesList = List.of(files);
        ArrayDeque<File> dirsToSearch = new ArrayDeque<>(filesList);
        ArrayDeque<File> listRemainDir = new ArrayDeque<>(filesList);
        do {
            File remain = listRemainDir.poll();
            if (remain == null) {
                continue;
            }
            if (remain.isDirectory()) {
                File[] subFiles = remain.listFiles();
                if (subFiles != null) {
                    List<File> subFilesList = List.of(subFiles);
                    listRemainDir.addAll(subFilesList);
                    dirsToSearch.addAll(subFilesList);
                }
            } else {
                var pathToCheck = remain.getAbsolutePath();
                String priorityStr = String.valueOf(getPriorityBySuffix(getSuffixByPath(pathToCheck)));
                checkIsMatchedAndAddToList(pathToCheck, priorityStr, searchTask);
            }
        } while (!listRemainDir.isEmpty() && !searchTask.shouldStopSearch.get());
        for (File eachDir : dirsToSearch) {
            if (searchTask.shouldStopSearch.get()) {
                return;
            }
            var pathToCheck = eachDir.getAbsolutePath();
            String priorityStr = String.valueOf(getPriorityBySuffix(getSuffixByPath(pathToCheck)));
            checkIsMatchedAndAddToList(pathToCheck, priorityStr, searchTask);
        }
    }

    /**
     * 搜索文件夹
     */
    private void searchStartMenu(SearchTask searchTask) {
        searchFolder(GetWindowsKnownFolder.INSTANCE.getKnownFolder("{A4115719-D62E-491D-AA7C-E74B8BE3B067}"),
                searchTask);
        searchFolder(GetWindowsKnownFolder.INSTANCE.getKnownFolder("{625B53C3-AB48-4EC1-BA1F-A1EF4146FC19}"),
                searchTask);
    }

    /**
     * 搜索桌面
     */
    private void searchDesktop(SearchTask searchTask) {
        searchFolder(GetWindowsKnownFolder.INSTANCE.getKnownFolder("{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"),
                searchTask);
        searchFolder(GetWindowsKnownFolder.INSTANCE.getKnownFolder("{C4AA340D-F20F-4863-AFEF-F87EF2E6BA25}"),
                searchTask);
    }

    /**
     * 搜索优先文件夹
     */
    private void searchPriorityFolder(SearchTask searchTask) {
        searchFolder(AllConfigs.getInstance().getConfigEntity().getPriorityFolder(), searchTask);
    }

    /**
     * 返回满足数据在minRecordNum-maxRecordNum之间的表可以被缓存的表
     *
     * @param disks                硬盘盘符
     * @param tableQueueByPriority 后缀优先级表，从高到低优先级逐渐降低
     * @param isStopCreateCache    是否停止
     * @param minRecordNum         最小数据量
     * @param maxRecordNum         最大数据量
     * @return key为[盘符, 表名, 优先级]，例如 [C,list10,9]，value为实际数据量所占的字节数
     */
    private LinkedHashMap<String, Integer> scanDatabaseAndSelectCacheTable(String[] disks,
                                                                           ConcurrentLinkedQueue<String> tableQueueByPriority,
                                                                           Supplier<Boolean> isStopCreateCache,
                                                                           @SuppressWarnings("SameParameterValue") int minRecordNum,
                                                                           int maxRecordNum) {
        if (minRecordNum > maxRecordNum) {
            throw new RuntimeException("minRecordNum > maxRecordNum");
        }
        //检查哪些表符合缓存条件，通过表权重依次向下排序
        LinkedHashMap<String, Integer> tableNeedCache = new LinkedHashMap<>();
        for (String diskPath : disks) {
            String disk = String.valueOf(diskPath.charAt(0));
            try (Statement stmt = SQLiteUtil.getStatement(disk)) {
                for (String tableName : tableQueueByPriority) {
                    for (SuffixPriorityPair suffixPriorityPair : priorityMap) {
                        if (isStopCreateCache.get()) {
                            return tableNeedCache;
                        }
                        boolean canBeCached;
                        try (ResultSet resultCount = stmt.executeQuery(
                                "SELECT COUNT(*) as total_num FROM " + tableName + " WHERE PRIORITY=" + suffixPriorityPair.priority)) {
                            if (resultCount.next()) {
                                final int num = resultCount.getInt("total_num");
                                canBeCached = num >= minRecordNum && num <= maxRecordNum;
                                databaseResultsCount.put(disk + "," + tableName + "," + suffixPriorityPair.priority, new AtomicInteger(num));
                            } else {
                                canBeCached = false;
                            }
                        }
                        if (!canBeCached) {
                            continue;
                        }
                        try (ResultSet resultsLength = stmt.executeQuery(
                                "SELECT SUM(LENGTH(PATH)) as total_bytes FROM " + tableName + " WHERE PRIORITY=" + suffixPriorityPair.priority)) {
                            if (resultsLength.next()) {
                                final int resultsBytes = resultsLength.getInt("total_bytes");
                                tableNeedCache.put(disk + "," + tableName + "," + suffixPriorityPair.priority, resultsBytes);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return tableNeedCache;
    }

    /**
     * 扫描数据库并添加缓存
     */
    private void saveTableCacheThread() {
        CachedThreadPoolUtil.getInstance().executeTask(() -> {
            EventManagement eventManagement = EventManagement.getInstance();
            final int checkTimeInterval = 10 * 60 * 1000; // 10 min
            final int startUpLatency = 10 * 1000; // 10s
            var startCheckInfo = new Object() {
                long startCheckTimeMills = System.currentTimeMillis() - checkTimeInterval + startUpLatency;
            };
            final Supplier<Boolean> isStopCreateCache =
                    () -> !eventManagement.notMainExit() ||
                            status.get() == Constants.Enums.DatabaseStatus.MANUAL_UPDATE ||
                            status.get() == Constants.Enums.DatabaseStatus.VACUUM;
            final Supplier<Boolean> isStartSaveCache =
                    () -> (System.currentTimeMillis() - startCheckInfo.startCheckTimeMills > checkTimeInterval &&
                            status.get() == Constants.Enums.DatabaseStatus.NORMAL &&
                            !GetHandle.INSTANCE.isForegroundFullscreen()) ||
                            (isDatabaseUpdated.get());
            final int createMemoryThreshold = 70;
            final int createGPUCacheThreshold = 50;
            final int freeGPUCacheThreshold = 70;
            while (eventManagement.notMainExit()) {
                if (isStartSaveCache.get()) {
                    if (isDatabaseUpdated.get()) {
                        isDatabaseUpdated.set(false);
                    }
                    startCheckInfo.startCheckTimeMills = System.currentTimeMillis();
                    if (isEnableGPUAccelerate) {
                        final int gpuMemUsage = GPUAccelerator.INSTANCE.getGPUMemUsage();
                        if (gpuMemUsage < createGPUCacheThreshold) {
                            createGpuCache(isStopCreateCache, createGPUCacheThreshold);
                        }
                    } else {
                        final double memoryUsage = SystemInfoUtil.getMemoryUsage();
                        if (memoryUsage * 100 < createMemoryThreshold) {
                            createMemoryCache(isStopCreateCache);
                        }
                    }
                } else {
                    if (isEnableGPUAccelerate) {
                        final int gpuMemUsage = GPUAccelerator.INSTANCE.getGPUMemUsage();
                        if (gpuMemUsage >= freeGPUCacheThreshold) {
                            // 防止显存占用超过70%后仍然扫描数据库
                            startCheckInfo.startCheckTimeMills = System.currentTimeMillis();
                            if (GPUAccelerator.INSTANCE.hasCache()) {
                                GPUClearCacheEvent gpuClearCacheEvent = new GPUClearCacheEvent();
                                eventManagement.putEvent(gpuClearCacheEvent);
                                eventManagement.waitForEvent(gpuClearCacheEvent);
                            }
                        }
                    }
                }
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private void createMemoryCache(Supplier<Boolean> isStopCreateCache) {
        System.out.println("添加缓存");
        String availableDisks = AllConfigs.getInstance().getAvailableDisks();
        ConcurrentLinkedQueue<String> tableQueueByPriority = initTableQueueByPriority();
        // 系统内存使用少于70%
        String[] disks = RegexUtil.comma.split(availableDisks);
        LinkedHashMap<String, Integer> tableNeedCache = scanDatabaseAndSelectCacheTable(disks,
                tableQueueByPriority,
                isStopCreateCache,
                100,
                2000);
        saveTableCache(isStopCreateCache, tableNeedCache);
        System.out.println("添加完成");
    }

    @SuppressWarnings("SameParameterValue")
    private void createGpuCache(Supplier<Boolean> isStopCreateCache, int createGpuCacheThreshold) {
        System.out.println("添加gpu缓存");
        String availableDisks = AllConfigs.getInstance().getAvailableDisks();
        ConcurrentLinkedQueue<String> tableQueueByPriority = initTableQueueByPriority();
        String[] disks = RegexUtil.comma.split(availableDisks);
        LinkedHashMap<String, Integer> tableNeedCache = scanDatabaseAndSelectCacheTable(disks,
                tableQueueByPriority,
                isStopCreateCache,
                1,
                Integer.MAX_VALUE);
        saveTableCacheForGPU(isStopCreateCache, tableNeedCache, createGpuCacheThreshold);
        System.out.println("添加完成");
    }

    /**
     * 缓存数据表到显存中
     *
     * @param isStopCreateCache 是否停止
     * @param tableNeedCache    需要缓存的表
     */
    private void saveTableCacheForGPU(Supplier<Boolean> isStopCreateCache,
                                      LinkedHashMap<String, Integer> tableNeedCache,
                                      int createGpuCacheThreshold) {
        for (Map.Entry<String, Cache> entry : tableCache.entrySet()) {
            String key = entry.getKey();
            if (tableNeedCache.containsKey(key)) {
                //超过128M字节或已存在缓存
                if (GPUAccelerator.INSTANCE.isCacheExist(key) || tableNeedCache.get(key) > 128 * 1024 * 1024) {
                    continue;
                }
                String[] info = RegexUtil.comma.split(key);
                try (Statement stmt = SQLiteUtil.getStatement(info[0]);
                     ResultSet resultSet = stmt.executeQuery("SELECT PATH FROM " + info[1] + " " + "WHERE PRIORITY=" + info[2])) {
                    EventManagement eventManagement = EventManagement.getInstance();
                    GPUAccelerator.INSTANCE.initCache(key, () -> {
                        try {
                            if (resultSet.next() && eventManagement.notMainExit()) {
                                return resultSet.getString("PATH");
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return null;
                    });
                    if (isStopCreateCache.get()) {
                        break;
                    }
                    var usage = GPUAccelerator.INSTANCE.getGPUMemUsage();
                    if (usage > createGpuCacheThreshold) {
                        break;
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 缓存数据表
     *
     * @param isStopCreateCache 是否停止
     * @param tableNeedCache    需要缓存的表
     */
    private void saveTableCache(Supplier<Boolean> isStopCreateCache, LinkedHashMap<String, Integer> tableNeedCache) {
        //开始缓存数据库表
        out:
        for (Map.Entry<String, Cache> entry : tableCache.entrySet()) {
            String key = entry.getKey();
            Cache cache = entry.getValue();
            if (tableNeedCache.containsKey(key)) {
                //当前表可以被缓存
                if (tableCacheCount.get() + tableNeedCache.get(key) < MAX_CACHED_RECORD_NUM - 1000 && !cache.isCacheValid()) {
                    cache.data = new ConcurrentLinkedQueue<>();
                    String[] info = RegexUtil.comma.split(key);
                    try (Statement stmt = SQLiteUtil.getStatement(info[0]);
                         ResultSet resultSet = stmt.executeQuery("SELECT PATH FROM " + info[1] + " " + "WHERE PRIORITY=" + info[2])) {
                        while (resultSet.next()) {
                            if (isStopCreateCache.get()) {
                                break out;
                            }
                            cache.data.add(resultSet.getString("PATH"));
                            tableCacheCount.incrementAndGet();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                    cache.isCached.set(true);
                    cache.isFileLost.set(false);
                }
            } else {
                if (cache.isCached.get()) {
                    cache.isCached.set(false);
                    int num = cache.data.size();
                    int tableCacheCountVal = tableCacheCount.get();
                    while (!tableCacheCount.compareAndSet(tableCacheCountVal, tableCacheCountVal - num)) {
                        tableCacheCountVal = tableCacheCount.get();
                        Thread.onSpinWait();
                    }
                    cache.data = null;
                }
            }
        }
    }

    private void syncFileChangesThread() {
        CachedThreadPoolUtil cachedThreadPoolUtil = CachedThreadPoolUtil.getInstance();
        EventManagement eventManagement = EventManagement.getInstance();
        var fileChanges = new SynchronousQueue<Runnable>();
        cachedThreadPoolUtil.executeTask(() -> addFileChangesRecords(fileChanges));
        cachedThreadPoolUtil.executeTask(() -> {
            while (eventManagement.notMainExit()) {
                try {
                    fileChanges.take().run();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @SneakyThrows
    private void addFileChangesRecords(SynchronousQueue<Runnable> fileChanges) {
        var eventManagement = EventManagement.getInstance();
        while (eventManagement.notMainExit()) {
            String addFilePath = FileMonitor.INSTANCE.pop_add_file();
            String deleteFilePath = FileMonitor.INSTANCE.pop_del_file();
            if (addFilePath != null) {
                File addFile = new File(addFilePath);
                do {
                    if (addFile.getParentFile() != null) {
                        File finalAddFile = addFile;
                        fileChanges.put(() -> addFileToDatabase(finalAddFile.getAbsolutePath()));
                    }
                } while ((addFile = addFile.getParentFile()) != null);
            }
            if (deleteFilePath != null) {
                fileChanges.put(() -> removeFileFromDatabase(deleteFilePath));
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
        fileChanges.put(() -> {
            //退出执行线程
        });
    }

    public Set<String> getCache() {
        return new LinkedHashSet<>(databaseCacheSet);
    }

    /**
     * 将缓存中的文件保存到cacheSet中
     */
    private void prepareDatabaseCache() {
        String eachLine;
        try (Statement statement = SQLiteUtil.getStatement("cache");
             ResultSet resultSet = statement.executeQuery("SELECT PATH FROM cache;")) {
            while (resultSet.next()) {
                eachLine = resultSet.getString("PATH");
                databaseCacheSet.add(eachLine);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从缓存中搜索结果并将匹配的放入listResults
     */
    private void searchCache(SearchTask searchTask) {
        for (String each : databaseCacheSet) {
            if (Files.exists(Path.of(each))) {
                var priorityStr = String.valueOf(getPriorityBySuffix(getSuffixByPath(each)));
                checkIsMatchedAndAddToList(each, priorityStr, searchTask);
            } else {
                EventManagement.getInstance().putEvent(new DeleteFromCacheEvent(each));
            }
            if (searchTask.shouldStopSearch.get()) {
                return;
            }
        }
    }

    /**
     * * 检查文件路径是否匹配然后加入到列表
     *
     * @param path 文件路径
     * @return true如果匹配成功
     */
    private boolean checkIsMatchedAndAddToList(String path,
                                               String priorityStr,
                                               SearchTask searchTask) {
        boolean ret = false;
        if (PathMatchUtil.check(path,
                searchTask.searchInfo.searchCase,
                searchTask.searchInfo.isIgnoreCase,
                searchTask.searchInfo.searchText,
                searchTask.searchInfo.keywords,
                searchTask.searchInfo.keywordsLowerCase,
                searchTask.searchInfo.isKeywordPath)) {
            //字符串匹配通过
            if (!FileUtil.isFileExist(path)) {
                removeFileFromDatabase(path);
            } else if (searchTask.tempResultsForEvent.add(path)) {
                ret = true;
                searchTask.priorityContainer.get(priorityStr).add(path);
                if (searchTask.tempResultsForEvent.size() >= MAX_RESULTS) {
                    searchTask.shouldStopSearch.set(true);
                }
            }
        }
        return ret;
    }

    /**
     * 搜索数据库并加入到tempQueue中
     *
     * @param sql sql
     */
    private int searchAndAddToTempResults(String sql,
                                          Statement stmt,
                                          String priorityStr,
                                          SearchTask searchTask) {
        int matchedResultCount = 0;
        //结果太多则不再进行搜索
        if (searchTask.shouldStopSearch.get()) {
            return matchedResultCount;
        }
        String[] tmpQueryResultsCache = new String[MAX_TEMP_QUERY_RESULT_CACHE];
        EventManagement eventManagement = EventManagement.getInstance();
        try (ResultSet resultSet = stmt.executeQuery(sql)) {
            boolean isExit = false;
            while (!isExit && eventManagement.notMainExit()) {
                int i = 0;
                // 先将结果查询出来，再进行字符串匹配，提高吞吐量
                while (i < MAX_TEMP_QUERY_RESULT_CACHE && eventManagement.notMainExit()) {
                    if (resultSet.next()) {
                        tmpQueryResultsCache[i] = resultSet.getString("PATH");
                        ++i;
                    } else {
                        isExit = true;
                        break;
                    }
                }
                for (int j = 0; j < i; ++j) {
                    if (checkIsMatchedAndAddToList(tmpQueryResultsCache[j], priorityStr, searchTask)) {
                        ++matchedResultCount;
                        //结果太多则不再进行搜索
                        //用户重新输入了信息
                        if (searchTask.shouldStopSearch.get()) {
                            return matchedResultCount;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("error sql : " + sql);
            e.printStackTrace();
        }
        return matchedResultCount;
    }

    /**
     * 根据优先级将表排序放入tableQueue
     */
    private ConcurrentLinkedQueue<String> initTableQueueByPriority() {
        ConcurrentLinkedQueue<String> tableQueue = new ConcurrentLinkedQueue<>();
        ArrayList<TableNameWeightInfo> tmpCommandList = new ArrayList<>(tableSet);
        //将tableSet通过权重排序
        tmpCommandList.sort((o1, o2) -> Long.compare(o2.weight.get(), o1.weight.get()));
        for (TableNameWeightInfo each : tmpCommandList) {
            if (IsDebug.isDebug()) {
                System.out.println("已添加表" + each.tableName + "----权重" + each.weight.get());
            }
            tableQueue.add(each.tableName);
        }
        return tableQueue;
    }

    /**
     * 初始化所有表名和权重信息，不要移动到构造函数中，否则会造成死锁
     * 在该任务前可能会有设置搜索框颜色等各种任务，这些任务被设置为异步，若在构造函数未执行完成时，会造成无法构造实例
     */
    private void initTableMap() {
        boolean isNeedSubtract = false;
        HashMap<String, Integer> weights = queryAllWeights();
        if (!weights.isEmpty()) {
            for (int i = 0; i <= Constants.ALL_TABLE_NUM; i++) {
                Integer weight = weights.get("list" + i);
                if (weight == null) {
                    weight = 0;
                }
                if (weight > 100_000_000) {
                    isNeedSubtract = true;
                }
                tableSet.add(new TableNameWeightInfo("list" + i, weight));
            }
        } else {
            for (int i = 0; i <= Constants.ALL_TABLE_NUM; i++) {
                tableSet.add(new TableNameWeightInfo("list" + i, 0));
            }
        }
        if (isNeedSubtract) {
            tableSet.forEach(tableNameWeightInfo -> tableNameWeightInfo.weight.set(tableNameWeightInfo.weight.get() / 2));
        }
    }

    /**
     * 根据上面分配的位信息，从第二位开始，与taskStatus做与运算，并向右偏移，若结果为1，则表示该任务完成
     */
    private void waitForTasks(SearchTask searchTask) {
        try {
            EventManagement eventManagement = EventManagement.getInstance();
            var resultsAdded = new ArrayList<String>();
            while (!searchTask.taskStatus.equals(searchTask.allTaskStatus) && eventManagement.notMainExit()) {
                for (var eachPriority : priorityMap) {
                    var priorityContainer = searchTask.priorityContainer.get(String.valueOf(eachPriority.priority));
                    for (String searchResult : priorityContainer) {
                        searchTask.tempResults.add(searchResult);
                        resultsAdded.add(searchResult);
                    }
                    priorityContainer.removeAll(resultsAdded);
                    resultsAdded.clear();
                }
                TimeUnit.MILLISECONDS.sleep(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            searchDone(searchTask);
        }
    }

    private void searchDone(SearchTask searchTask) {
        EventManagement eventManagement = EventManagement.getInstance();
        eventManagement.putEvent(new SearchDoneEvent(new ConcurrentLinkedQueue<>(searchTask.tempResultsForEvent)));
        if (isEnableGPUAccelerate && eventManagement.notMainExit()) {
            GPUAccelerator.INSTANCE.stopCollectResults();
        }
        searchTask.shouldStopSearch.set(true);
    }

    /**
     * 解析出sql中的priority
     * SELECT PATH FROM list[num] where priority=[priority];
     *
     * @param sql sql
     */
    private String getPriorityFromSelectSql(String sql) {
        final int pos = sql.indexOf('=');
        if (pos == -1) {
            throw new RuntimeException("error sql no priority");
        }
        return sql.substring(pos + 1, sql.length() - 1);
    }

    /**
     * 创建搜索任务
     * nonFormattedSql将会生成从list0-40，根据priority从高到低排序的SQL语句，第一个map中key保存未格式化的sql，value保存表名称
     * 生成任务顺序会根据list的权重和priority来生成
     * <p>
     * taskStatus      用于保存任务信息，这是一个通用变量，从第二个位开始，每一个位代表一个任务，当任务完成，该位将被置为1，否则为0，例如第一个和第三个任务完成，第二个未完成，则为1010
     * allTaskStatus   所有的任务信息，从第二位开始，只要有任务被创建，该位就为1，例如三个任务被创建，则为1110
     * taskMap         任务
     *
     * @param nonFormattedSql 未格式化搜索字段的SQL
     */
    private void addSearchTasks(ArrayList<LinkedHashMap<String, String>> nonFormattedSql, SearchTask searchTask) {
        Bit taskNumber = new Bit(new byte[]{1});
        AllConfigs allConfigs = AllConfigs.getInstance();
        String availableDisks = allConfigs.getAvailableDisks();
        for (String eachDisk : RegexUtil.comma.split(availableDisks)) {
            ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
            searchTask.taskMap.put(eachDisk, tasks);
            //向任务队列tasks添加任务
            for (var commandsMap : nonFormattedSql) {
                //为每个任务分配的位，不断左移以不断进行分配
                taskNumber.shiftLeft(1);
                Bit currentTaskNum = new Bit(taskNumber);
                //记录当前任务信息到allTaskStatus
                byte[] origin;
                while ((origin = searchTask.allTaskStatus.getBytes()) != null) {
                    Bit or = Bit.or(origin, currentTaskNum.getBytes());
                    if (searchTask.allTaskStatus.compareAndSet(origin, or)) {
                        break;
                    }
                }
                //每一个任务负责查询一个priority和list0-list40生成的41个SQL
                addTaskForDatabase0(eachDisk, tasks, commandsMap, currentTaskNum, searchTask);
                if (searchTask.shouldStopSearch.get()) {
                    return;
                }
            }
        }
    }

    private void addTaskForDatabase0(String diskChar,
                                     ConcurrentLinkedQueue<Runnable> tasks,
                                     LinkedHashMap<String, String> sqlToExecute,
                                     Bit currentTaskNum,
                                     SearchTask searchTask) {
        tasks.add(() -> {
            Statement stmt = null;
            for (var sqlAndTableName : sqlToExecute.entrySet()) {
                String diskStr = String.valueOf(diskChar.charAt(0));
                String eachSql = sqlAndTableName.getKey();
                String tableName = sqlAndTableName.getValue();
                String priority = getPriorityFromSelectSql(eachSql);
                String key = diskStr + "," + tableName + "," + priority;
                final long matchedNum;
                if (isEnableGPUAccelerate && GPUAccelerator.INSTANCE.isMatchDone(key)) {
                    //gpu搜索已经放入container，只需要获取matchedNum修改权重即可
                    matchedNum = GPUAccelerator.INSTANCE.matchedNumber(key);
                } else {
                    if (!searchTask.shouldStopSearch.get()) {
                        int recordsNum = 1;
                        if (databaseResultsCount.containsKey(key)) {
                            recordsNum = databaseResultsCount.get(key).get();
                        }
                        if (recordsNum != 0) {
                            if (stmt == null) {
                                try {
                                    stmt = SQLiteUtil.getStatement(diskStr);
                                } catch (SQLException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            matchedNum = searchFromDatabaseOrCache(stmt, eachSql, key, priority, searchTask);
                        } else {
                            matchedNum = 0;
                        }
                    } else {
                        matchedNum = 0;
                    }
                }
                final long weight = Math.min(matchedNum, 5);
                if (weight != 0L) {
                    //更新表的权重，每次搜索将会按照各个表的权重排序
                    updateTableWeight(tableName, weight);
                }
            }
            //执行完后将对应的线程flag设为1
            byte[] originalBytes;
            while ((originalBytes = searchTask.taskStatus.getBytes()) != null) {
                Bit or = Bit.or(originalBytes, currentTaskNum.getBytes());
                if (searchTask.taskStatus.compareAndSet(originalBytes, or)) {
                    break;
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 从数据库中查找
     *
     * @param stmt statement
     * @param sql  sql
     * @param key  查询key，例如 [C,list10,-1]
     * @return 查询的结果数量
     */
    private long searchFromDatabaseOrCache(Statement stmt, String sql, String key, String priority, SearchTask searchTask) {
        if (searchTask.shouldStopSearch.get()) {
            return 0;
        }
        long matchedNum;
        Cache cache = tableCache.get(key);
        if (cache != null && cache.isCacheValid()) {
            if (IsDebug.isDebug()) {
                System.out.println("从缓存中读取 " + key);
            }
            matchedNum = cache.data.stream()
                    .filter(e -> checkIsMatchedAndAddToList(e, priority, searchTask))
                    .count();
        } else {
            //格式化是为了以后的拓展性
            String formattedSql = String.format(sql, "PATH");
            //当前数据库表中有多少个结果匹配成功
            matchedNum = searchAndAddToTempResults(formattedSql, stmt, priority, searchTask);
        }
        return matchedNum;
    }

    /**
     * 生成未格式化的sql
     * 第一个map中每一个priority加上list0-list40会生成41条SQL作为key，value是搜索的表，即SELECT* FROM [list?]中的[list?];
     *
     * @return set
     */
    private ArrayList<LinkedHashMap<String, String>> getNonFormattedSqlFromTableQueue(SearchInfo searchInfo) {
        ArrayList<LinkedHashMap<String, String>> sqlColumnMap = new ArrayList<>();
        if (priorityMap.isEmpty()) {
            return sqlColumnMap;
        }
        ConcurrentLinkedQueue<String> tableQueue = initTableQueueByPriority();
        int asciiSum = 0;
        if (searchInfo.keywords != null) {
            for (String keyword : searchInfo.keywords) {
                int ascII = GetAscII.INSTANCE.getAscII(keyword); //其实是utf8编码的值
                asciiSum += Math.max(ascII, 0);
            }
        }
        int asciiGroup = asciiSum / 100;
        if (asciiGroup > Constants.ALL_TABLE_NUM) {
            asciiGroup = Constants.ALL_TABLE_NUM;
        }
        String firstTableName = "list" + asciiGroup;
        // 有d代表只需要搜索文件夹，文件夹的priority为-1
        if (searchInfo.searchCase != null && Arrays.asList(searchInfo.searchCase).contains("d")) {
            //首先根据输入的keywords找到对应的list
            LinkedHashMap<String, String> tmpPriorityMap = new LinkedHashMap<>();
            String eachSql = "SELECT %s FROM " + firstTableName + " WHERE PRIORITY=" + "-1;";
            tmpPriorityMap.put(eachSql, firstTableName);
            tableQueue.stream().filter(each -> !each.equals(firstTableName)).forEach(each -> {
                // where后面=不能有空格，否则解析priority会出错
                String sql = "SELECT %s FROM " + each + " WHERE PRIORITY=" + "-1;";
                tmpPriorityMap.put(sql, each);
            });
            sqlColumnMap.add(tmpPriorityMap);
        } else {
            for (SuffixPriorityPair i : priorityMap) {
                LinkedHashMap<String, String> eachPriorityMap = new LinkedHashMap<>();
                String eachSql = "SELECT %s FROM " + firstTableName + " WHERE PRIORITY=" + i.priority + ";";
                eachPriorityMap.put(eachSql, firstTableName);
                tableQueue.stream().filter(each -> !each.equals(firstTableName)).forEach(each -> {
                    // where后面=不能有空格，否则解析priority会出错
                    String sql = "SELECT %s FROM " + each + " WHERE PRIORITY=" + i.priority + ";";
                    eachPriorityMap.put(sql, each);
                });
                sqlColumnMap.add(eachPriorityMap);
            }
        }
        return sqlColumnMap;
    }

    private void startSearchThread() {
        CachedThreadPoolUtil.getInstance().executeTask(() -> {
            var eventManagement = EventManagement.getInstance();
            while (eventManagement.notMainExit()) {
                var searchTask = searchTasksQueue.poll();
                if (searchTask == null) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }
                startSearch(searchTask);
            }
        });
    }

    /**
     * 添加sql语句，并开始搜索
     */
    private void startSearch(SearchTask searchTask) {
        startSearchTimeMills = System.currentTimeMillis();
        var cachedThreadPoolUtil = CachedThreadPoolUtil.getInstance();
        var eventManagement = EventManagement.getInstance();
        final int threadNumberPerDisk = Math.max(1, AllConfigs.getInstance().getConfigEntity().getSearchThreadNumber() / searchTask.taskMap.size()) * 2;
        Consumer<ConcurrentLinkedQueue<Runnable>> taskHandler = (taskQueue) -> {
            while (!taskQueue.isEmpty() && eventManagement.notMainExit()) {
                var runnable = taskQueue.poll();
                if (runnable == null) {
                    continue;
                }
                runnable.run();
            }
        };
        for (var entry : searchTask.taskMap.entrySet()) {
            for (int i = 0; i < threadNumberPerDisk; i++) {
                cachedThreadPoolUtil.executeTask(() -> {
                    searchThreadCount.incrementAndGet();
                    var taskQueue = entry.getValue();
                    taskHandler.accept(taskQueue);
                    searchTask.taskMap.remove(entry.getKey());
                    //自身任务已经完成，开始扫描其他线程的任务
                    boolean hasTask;
                    do {
                        hasTask = false;
                        for (var e : searchTask.taskMap.entrySet()) {
                            var otherTaskQueue = e.getValue();
                            // 如果hasTask为false，则检查otherTaskQueue是否为空
                            if (!hasTask) {
                                // 不为空则设置hasTask为true
                                hasTask = !otherTaskQueue.isEmpty();
                            }
                            taskHandler.accept(otherTaskQueue);
                            searchTask.taskMap.remove(e.getKey());
                        }
                    } while (hasTask && eventManagement.notMainExit());
                    searchThreadCount.decrementAndGet();
                });
            }
        }
        waitForTasks(searchTask);
    }

    /**
     * 生成删除记录sql
     *
     * @param asciiSum ascii
     * @param path     文件路径
     */
    private void addDeleteSqlCommandByAscii(int asciiSum, String path) {
        String command;
        int asciiGroup = asciiSum / 100;
        asciiGroup = Math.min(asciiGroup, Constants.ALL_TABLE_NUM);
        String sql = "DELETE FROM %s where PATH=\"%s\";";
        command = String.format(sql, "list" + asciiGroup, path);
        if (command != null && isCommandNotRepeat(command)) {
            String disk = String.valueOf(path.charAt(0));
            SQLWithTaskId sqlWithTaskId = new SQLWithTaskId(command, SqlTaskIds.DELETE_FROM_LIST, disk);
            sqlWithTaskId.key = disk + "," + "list" + asciiGroup + "," + getPriorityBySuffix(getSuffixByPath(path));
            addToCommandQueue(sqlWithTaskId);
        }
    }

    /**
     * 生成添加记录sql
     *
     * @param asciiSum ascii
     * @param path     文件路径
     * @param priority 优先级
     */
    private void addAddSqlCommandByAscii(int asciiSum, String path, int priority) {
        String commandTemplate = "INSERT OR IGNORE INTO %s VALUES(%d, \"%s\", %d)";
        int asciiGroup = asciiSum / 100;
        asciiGroup = Math.min(asciiGroup, Constants.ALL_TABLE_NUM);
        String columnName = "list" + asciiGroup;
        String command = String.format(commandTemplate, columnName, asciiSum, path, priority);
        if (command != null && isCommandNotRepeat(command)) {
            String disk = String.valueOf(path.charAt(0));
            SQLWithTaskId sqlWithTaskId = new SQLWithTaskId(command, SqlTaskIds.INSERT_TO_LIST, String.valueOf(path.charAt(0)));
            sqlWithTaskId.key = disk + "," + "list" + asciiGroup + "," + getPriorityBySuffix(getSuffixByPath(path));
            addToCommandQueue(sqlWithTaskId);
        }
    }

    /**
     * 获得文件名
     *
     * @param path 文件路径
     * @return 文件名
     */
    private String getFileName(String path) {
        if (path != null) {
            int index = path.lastIndexOf(File.separator);
            return path.substring(index + 1);
        }
        return "";
    }

    private int getAscIISum(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        return GetAscII.INSTANCE.getAscII(path);
    }

    /**
     * 检查要删除的文件是否还未添加
     * 防止文件刚添加就被删除
     *
     * @param path 待删除文件路径
     * @return true如果待删除文件已经在任务队列中
     */
    private boolean isRemoveFileInCommandQueue(String path, SQLWithTaskId[] sqlWithTaskId) {
        for (SQLWithTaskId each : sqlCommandQueue) {
            if (each.taskId == SqlTaskIds.INSERT_TO_LIST && each.sql.contains(path)) {
                sqlWithTaskId[0] = each;
                return true;
            }
        }
        return false;
    }

    /**
     * 从数据库中删除记录
     *
     * @param path 文件路径
     */
    private void removeFileFromDatabase(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        int asciiSum = getAscIISum(getFileName(path));
        SQLWithTaskId[] sqlWithTaskId = new SQLWithTaskId[1];
        if (isRemoveFileInCommandQueue(path, sqlWithTaskId)) {
            sqlCommandQueue.remove(sqlWithTaskId[0]);
        } else {
            addDeleteSqlCommandByAscii(asciiSum, path);
            int priorityBySuffix = getPriorityBySuffix(getSuffixByPath(path));
            int asciiGroup = asciiSum / 100;
            asciiGroup = Math.min(asciiGroup, Constants.ALL_TABLE_NUM);
            String tableName = "list" + asciiGroup;
            String key = path.charAt(0) + "," + tableName + "," + priorityBySuffix;
            if (isEnableGPUAccelerate) {
                EventManagement.getInstance().putEvent(new GPURemoveRecordEvent(key, path));
            }
            Cache cache = tableCache.get(key);
            if (cache.isCached.get()) {
                if (cache.data.remove(path)) {
                    tableCacheCount.decrementAndGet();
                }
            }
        }
    }

    public HashMap<String, Integer> getPriorityMap() {
        HashMap<String, Integer> map = new HashMap<>();
        priorityMap.forEach(p -> map.put(p.suffix, p.priority));
        return map;
    }

    /**
     * 初始化优先级表
     */
    private void initPriority() {
        priorityMap.clear();
        try (Statement stmt = SQLiteUtil.getStatement("cache");
             ResultSet resultSet = stmt.executeQuery("SELECT * FROM priority order by PRIORITY desc;")) {
            while (resultSet.next()) {
                String suffix = resultSet.getString("SUFFIX");
                String priority = resultSet.getString("PRIORITY");
                try {
                    priorityMap.add(new SuffixPriorityPair(suffix, Integer.parseInt(priority)));
                } catch (Exception e) {
                    e.printStackTrace();
                    priorityMap.add(new SuffixPriorityPair(suffix, 0));
                }
            }
            priorityMap.add(new SuffixPriorityPair("dirPriority", -1));
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
    }

    /**
     * 根据文件后缀获取优先级信息
     *
     * @param suffix 文件后缀名
     * @return 优先级
     */
    @SuppressWarnings("IndexOfReplaceableByContains")
    private int getPriorityBySuffix(String suffix) {
        List<SuffixPriorityPair> result = priorityMap.stream().filter(each -> each.suffix.equals(suffix)).toList();
        if (result.isEmpty()) {
            if (suffix.indexOf(File.separator) != -1) {
                return getPriorityBySuffix("dirPriority");
            } else {
                return getPriorityBySuffix("defaultPriority");
            }
        } else {
            return result.get(0).priority;
        }
    }

    /**
     * 获取文件后缀
     *
     * @param path 文件路径
     * @return 后缀名
     */
    private String getSuffixByPath(String path) {
        return path.substring(path.lastIndexOf('.') + 1).toLowerCase();
    }

    private void addFileToDatabase(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        int asciiSum = getAscIISum(getFileName(path));
        int priorityBySuffix = getPriorityBySuffix(getSuffixByPath(path));
        addAddSqlCommandByAscii(asciiSum, path, priorityBySuffix);
        int asciiGroup = asciiSum / 100;
        asciiGroup = Math.min(asciiGroup, Constants.ALL_TABLE_NUM);
        String tableName = "list" + asciiGroup;
        String key = path.charAt(0) + "," + tableName + "," + priorityBySuffix;
        if (isEnableGPUAccelerate) {
            EventManagement.getInstance().putEvent(new GPUAddRecordEvent(key, path));
        }
        Cache cache = tableCache.get(key);
        if (cache.isCacheValid()) {
            if (tableCacheCount.get() < MAX_CACHED_RECORD_NUM) {
                if (cache.data.add(path)) {
                    tableCacheCount.incrementAndGet();
                }
            } else {
                cache.isFileLost.set(true);
            }
        }
    }

    private void addFileToCache(String path) {
        String command = "INSERT OR IGNORE INTO cache(PATH) VALUES(\"" + path + "\");";
        if (isCommandNotRepeat(command)) {
            addToCommandQueue(new SQLWithTaskId(command, SqlTaskIds.INSERT_TO_CACHE, "cache"));
            if (IsDebug.isDebug()) {
                System.out.println("添加" + path + "到缓存");
            }
        }
    }

    private void removeFileFromCache(String path) {
        String command = "DELETE from cache where PATH=" + "\"" + path + "\";";
        if (isCommandNotRepeat(command)) {
            addToCommandQueue(new SQLWithTaskId(command, SqlTaskIds.DELETE_FROM_CACHE, "cache"));
            if (IsDebug.isDebug()) {
                System.out.println("删除" + path + "到缓存");
            }
        }
    }

    /**
     * 发送立即执行所有sql信号
     */
    private void sendExecuteSQLSignal() {
        isExecuteSqlImmediately.set(true);
    }

    /**
     * 执行sql
     */
    @SuppressWarnings("SqlNoDataSourceInspection")
    private void executeAllCommands() {
        synchronized (this) {
            if (!sqlCommandQueue.isEmpty()) {
                LinkedHashSet<SQLWithTaskId> tempCommandSet = new LinkedHashSet<>(sqlCommandQueue);
                HashMap<String, Statement> statementHashMap = new HashMap<>();

                tempCommandSet.forEach(sqlWithTaskId -> {
                    Statement stmt;
                    try {
                        if (statementHashMap.containsKey(sqlWithTaskId.diskStr)) {
                            stmt = statementHashMap.get(sqlWithTaskId.diskStr);
                        } else {
                            stmt = SQLiteUtil.getStatement(sqlWithTaskId.diskStr);
                            statementHashMap.put(sqlWithTaskId.diskStr, stmt);
                            stmt.execute("BEGIN;");
                        }
                        if (IsDebug.isDebug()) {
                            System.out.println("----------------------------------------------");
                            System.out.println("执行SQL命令--" + sqlWithTaskId.sql);
                            System.out.println("----------------------------------------------");
                        }
                        if (!stmt.execute(sqlWithTaskId.sql)) {
                            int updateCount = stmt.getUpdateCount();
                            if (sqlWithTaskId.key != null && updateCount != -1 && updateCount != 0) {
                                if (databaseResultsCount.containsKey(sqlWithTaskId.key)) {
                                    var recordsNumber = databaseResultsCount.get(sqlWithTaskId.key);
                                    updateCount = sqlWithTaskId.taskId == SqlTaskIds.INSERT_TO_LIST ? updateCount : -updateCount;
                                    recordsNumber.addAndGet(updateCount);
                                }
                            }
                        }
                    } catch (SQLException exception) {
                        exception.printStackTrace();
                    }
                });
                statementHashMap.forEach((k, v) -> {
                    try {
                        v.execute("COMMIT;");
                        v.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
                sqlCommandQueue.removeAll(tempCommandSet);
            }
        }
    }

    /**
     * 添加任务到任务列表
     *
     * @param sql 任务
     */
    private void addToCommandQueue(SQLWithTaskId sql) {
        if (sqlCommandQueue.size() < MAX_SQL_NUM) {
            if (getStatus() == Constants.Enums.DatabaseStatus.MANUAL_UPDATE) {
                return;
            }
            sqlCommandQueue.add(sql);
        } else {
            if (IsDebug.isDebug()) {
                System.err.println("添加sql语句" + sql + "失败，已达到最大上限");
            }
        }
    }

    /**
     * 检查任务是否重复
     *
     * @param sql 任务
     * @return boolean
     */
    private boolean isCommandNotRepeat(String sql) {
        for (SQLWithTaskId each : sqlCommandQueue) {
            if (each.sql.equals(sql)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取数据库状态
     *
     * @return 数据库状态
     */
    public Constants.Enums.DatabaseStatus getStatus() {
        var currentStatus = status.get();
        return switch (currentStatus) {
            case NORMAL, _TEMP -> Constants.Enums.DatabaseStatus.NORMAL;
            default -> currentStatus;
        };
    }

    public boolean casSetStatus(Constants.Enums.DatabaseStatus expect, Constants.Enums.DatabaseStatus newVal) {
        final long start = System.currentTimeMillis();
        final long timeout = 1000;
        try {
            while (!status.compareAndSet(expect, newVal) && System.currentTimeMillis() - start < timeout) {
                TimeUnit.MILLISECONDS.sleep(1);
            }
        } catch (InterruptedException ignored) {
            // ignore interruptedException
        }
        return status.get() == newVal;
    }

    /**
     * 创建索引
     */
    private void createAllIndex() {
        sqlCommandQueue.add(new SQLWithTaskId("CREATE INDEX IF NOT EXISTS cache_index ON cache(PATH);", SqlTaskIds.CREATE_INDEX, "cache"));
        for (String each : RegexUtil.comma.split(AllConfigs.getInstance().getAvailableDisks())) {
            for (int i = 0; i <= Constants.ALL_TABLE_NUM; ++i) {
                String createIndex = "CREATE INDEX IF NOT EXISTS list" + i + "_index ON list" + i + "(PRIORITY);";
                sqlCommandQueue.add(new SQLWithTaskId(createIndex, SqlTaskIds.CREATE_INDEX, String.valueOf(each.charAt(0))));
            }
        }
    }

    /**
     * 调用C程序搜索并等待执行完毕
     *
     * @param paths      磁盘信息
     * @param ignorePath 忽略文件夹
     * @throws IOException exception
     */
    private Process searchByUSN(String paths, String ignorePath) throws IOException {
        File usnSearcher = new File("user/fileSearcherUSN.exe");
        String absPath = usnSearcher.getAbsolutePath();
        String start = absPath.substring(0, 2);
        String end = "\"" + absPath.substring(2) + "\"";
        File database = new File("data");
        try (BufferedWriter buffW = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("user/MFTSearchInfo.dat"), StandardCharsets.UTF_8))) {
            buffW.write(paths);
            buffW.newLine();
            buffW.write(database.getAbsolutePath());
            buffW.newLine();
            buffW.write(ignorePath);
        }
        return Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", start + end}, null, new File("user"));
    }

    /**
     * 检查索引数据库大小
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void checkDbFileSize(boolean isDropPrevious) {
        String databaseCreateTimeFileName = "user/databaseCreateTime.dat";
        HashMap<String, String> databaseCreateTimeMap = new HashMap<>();
        String[] disks = RegexUtil.comma.split(AllConfigs.getInstance().getAvailableDisks());
        LocalDate now = LocalDate.now();
        //从文件中读取每个数据库的创建时间
        StringBuilder stringBuilder = new StringBuilder();
        Gson gson = GsonUtil.INSTANCE.getGson();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(databaseCreateTimeFileName), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            for (String disk : disks) {
                databaseCreateTimeMap.put(disk, now.toString());
            }
            Map map = gson.fromJson(stringBuilder.toString(), Map.class);
            if (map != null) {
                //从文件中读取每个数据库的创建时间
                map.forEach((disk, createTime) -> databaseCreateTimeMap.put((String) disk, (String) createTime));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        final long maxDatabaseSize = 8L * 1024 * 1024 * 100;
        for (String eachDisk : disks) {
            String name = eachDisk.charAt(0) + ".db";
            try {
                Path diskDatabaseFile = Path.of("data/" + name);
                long length = Files.size(diskDatabaseFile);
                if (length > maxDatabaseSize ||
                        Period.between(LocalDate.parse(databaseCreateTimeMap.get(eachDisk)), now).getDays() > 5 ||
                        isDropPrevious) {
                    if (IsDebug.isDebug()) {
                        System.out.println("当前文件" + name + "已删除");
                    }
                    //更新创建时间
                    databaseCreateTimeMap.put(eachDisk, now.toString());
                    Files.delete(diskDatabaseFile);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        String toJson = gson.toJson(databaseCreateTimeMap);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(databaseCreateTimeFileName), StandardCharsets.UTF_8))) {
            writer.write(toJson);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SneakyThrows
    private void executeAllSQLAndWait(@SuppressWarnings("SameParameterValue") int timeoutMills) {// 等待剩余的sql全部执行完成
        final long time = System.currentTimeMillis();
        // 将在队列中的sql全部执行并等待搜索线程全部完成
        System.out.println("等待所有sql执行完成，并且退出搜索");
        while (searchThreadCount.get() != 0 || !sqlCommandQueue.isEmpty()) {
            sendExecuteSQLSignal();
            TimeUnit.MILLISECONDS.sleep(10);
            if (System.currentTimeMillis() - time > timeoutMills) {
                System.out.println("等待超时");
                break;
            }
        }
    }

    private void stopAllSearch() {
        for (SearchTask searchTask : searchTasksQueue) {
            searchTask.shouldStopSearch.set(true);
        }
    }

    /**
     * 等待fileSearcherUSN进程并切换回数据库
     * @param searchByUsn fileSearcherUSN进程
     */
    private void waitForSearchAndSwitchDatabase(Process searchByUsn) {
        // 搜索完成并写入数据库后，重新建立数据库连接
        try {
            ProcessUtil.waitForProcess("fileSearcherUSN.exe", 1000);
            readSearchUsnOutput(searchByUsn);
        } catch (Exception e) {
            e.printStackTrace();
        }
        stopAllSearch();
        casSetStatus(this.status.get(), Constants.Enums.DatabaseStatus.MANUAL_UPDATE);
        try {
            final long startWaitingTime = System.currentTimeMillis();
            //等待所有搜索线程结束，最多等待1分钟
            while (searchThreadCount.get() != 0 && System.currentTimeMillis() - startWaitingTime < 60 * 1000) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
        } catch (InterruptedException ignored) {
            // ignore interrupt exception
        }
        SQLiteUtil.closeAll();
        invalidateAllCache();
        SQLiteUtil.initAllConnections();
        createAllIndex();
        sendExecuteSQLSignal();
        waitForCommandSet(SqlTaskIds.CREATE_INDEX);
        // 搜索完成，更新isDatabaseUpdated标志
        isDatabaseUpdated.set(true);
        //重新初始化priority
        initPriority();
        casSetStatus(this.status.get(), Constants.Enums.DatabaseStatus.NORMAL);
    }

    private static void readSearchUsnOutput(Process searchByUsn) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(searchByUsn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("fileSearcherUSN: " + line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(searchByUsn.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.err.println("fileSearcherUSN: " + line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 关闭数据库连接并更新数据库
     *
     * @param ignorePath     忽略文件夹
     * @param isDropPrevious 是否删除之前的记录
     */
    private boolean updateLists(String ignorePath, boolean isDropPrevious) throws IOException, InterruptedException {
        if (getStatus() == Constants.Enums.DatabaseStatus.MANUAL_UPDATE || ProcessUtil.isProcessExist("fileSearcherUSN.exe")) {
            throw new RuntimeException("already searching");
        }
        // 复制数据库到tmp
        SQLiteUtil.copyDatabases("data", "tmp");
        if (!casSetStatus(status.get(), Constants.Enums.DatabaseStatus.MANUAL_UPDATE)) {
            throw new RuntimeException("databaseService status设置MANUAL UPDATE状态失败");
        }
        // 停止搜索
        stopAllSearch();
        executeAllSQLAndWait(3000);

        if (!isDropPrevious) {
            //执行VACUUM命令
            for (String eachDisk : RegexUtil.comma.split(AllConfigs.getInstance().getAvailableDisks())) {
                try (Statement stmt = SQLiteUtil.getStatement(String.valueOf(eachDisk.charAt(0)))) {
                    stmt.execute("VACUUM;");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        SQLiteUtil.closeAll();
        SQLiteUtil.initAllConnections("tmp");
        if (IsDebug.isDebug()) {
            System.out.println("成功切换到临时数据库");
        }
        if (!casSetStatus(status.get(), Constants.Enums.DatabaseStatus._TEMP)) {
            //恢复data目录的数据库
            SQLiteUtil.closeAll();
            SQLiteUtil.initAllConnections();
            casSetStatus(status.get(), Constants.Enums.DatabaseStatus.NORMAL);
            throw new RuntimeException("databaseService status设置TEMP状态失败");
        }
        // 检查数据库文件大小，过大则删除
        checkDbFileSize(isDropPrevious);

        Process searchByUSN = null;
        try {
            // 创建搜索进程并等待
            searchByUSN = searchByUSN(AllConfigs.getInstance().getAvailableDisks(), ignorePath.toLowerCase());
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            waitForSearchAndSwitchDatabase(searchByUSN);
        }
        return true;
    }

    /**
     * 等待sql任务执行
     *
     * @param taskId 任务id
     */
    @SneakyThrows
    private void waitForCommandSet(@SuppressWarnings("SameParameterValue") SqlTaskIds taskId) {
        EventManagement eventManagement = EventManagement.getInstance();
        long tmpStartTime = System.currentTimeMillis();
        while (eventManagement.notMainExit()) {
            //等待
            if (System.currentTimeMillis() - tmpStartTime > 60 * 1000) {
                System.err.println("等待SQL语句任务" + taskId + "处理超时");
                break;
            }
            //判断commandSet中是否还有taskId存在
            if (!isTaskExistInCommandSet(taskId)) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    /**
     * 获取缓存数量
     *
     * @return cache num
     */
    public int getDatabaseCacheNum() {
        return databaseCacheSet.size();
    }

    private boolean isTaskExistInCommandSet(SqlTaskIds taskId) {
        for (SQLWithTaskId tasks : sqlCommandQueue) {
            if (tasks.taskId == taskId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取list0-40所有表的权重，使用越频繁权重越高
     * @return map
     */
    private HashMap<String, Integer> queryAllWeights() {
        HashMap<String, Integer> stringIntegerHashMap = new HashMap<>();
        try (Statement pStmt = SQLiteUtil.getStatement("weight");
             ResultSet resultSet = pStmt.executeQuery("SELECT TABLE_NAME, TABLE_WEIGHT FROM weight;")) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                int weight = resultSet.getInt("TABLE_WEIGHT");
                stringIntegerHashMap.put(tableName, weight);
            }
        } catch (SQLException exception) {
            exception.printStackTrace();
        }
        return stringIntegerHashMap;
    }

    private void checkTimeAndSendExecuteSqlSignalThread() {
        CachedThreadPoolUtil.getInstance().executeTask(() -> {
            // 时间检测线程
            AllConfigs allConfigs = AllConfigs.getInstance();
            final long timeout = Constants.CLOSE_DATABASE_TIMEOUT_MILLS - 30 * 1000;
            EventManagement eventManagement = EventManagement.getInstance();
            long checkTime = System.currentTimeMillis();
            while (eventManagement.notMainExit()) {
                final long updateTimeLimit = allConfigs.getConfigEntity().getUpdateTimeLimit() * 1000L;
                if (System.currentTimeMillis() - checkTime >= updateTimeLimit) {
                    checkTime = System.currentTimeMillis();
                    boolean isTooManySQLsToExecute =
                            getStatus() == Constants.Enums.DatabaseStatus.NORMAL && sqlCommandQueue.size() > 100;
                    boolean isDatabaseNotClosed =
                            getStatus() == Constants.Enums.DatabaseStatus.NORMAL &&
                                    System.currentTimeMillis() - startSearchTimeMills < timeout;
                    if (isDatabaseNotClosed || isTooManySQLsToExecute) {
                        sendExecuteSQLSignal();
                    }
                }
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * 检查是否拥有管理员权限
     *
     * @return boolean
     */
    private static boolean isAdmin() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe");
            Process process = processBuilder.start();
            try (PrintStream printStream = new PrintStream(process.getOutputStream(), true)) {
                try (Scanner scanner = new Scanner(process.getInputStream())) {
                    printStream.println("@echo off");
                    printStream.println(">nul 2>&1 \"%SYSTEMROOT%\\system32\\cacls.exe\" \"%SYSTEMROOT%\\system32\\config\\system\"");
                    printStream.println("echo %errorlevel%");
                    boolean printedErrorLevel = false;
                    while (true) {
                        String nextLine = scanner.nextLine();
                        if (printedErrorLevel) {
                            int errorLevel = Integer.parseInt(nextLine);
                            scanner.close();
                            return errorLevel == 0;
                        } else if ("echo %errorlevel%".equals(nextLine)) {
                            printedErrorLevel = true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 根据输入生成SearchInfo
     * @param searchTextSupplier 全字匹配关键字
     * @param searchCaseSupplier 搜索过滤类型
     * @param keywordsSupplier 搜索关键字
     * @return SearchInfo
     */
    private static SearchInfo prepareSearchKeywords(Supplier<String> searchTextSupplier,
                                                    Supplier<String[]> searchCaseSupplier,
                                                    Supplier<String[]> keywordsSupplier) {
        var searchText = searchTextSupplier.get();
        var searchCase = searchCaseSupplier.get();
        var isIgnoreCase = searchCase == null ||
                Arrays.stream(searchCase).noneMatch(s -> s.equals(PathMatchUtil.SearchCase.CASE));
        String[] _keywords = keywordsSupplier.get();
        var keywords = new String[_keywords.length];
        var keywordsLowerCase = new String[_keywords.length];
        var isKeywordPath = new boolean[_keywords.length];
        // 对keywords进行处理
        for (int i = 0; i < _keywords.length; ++i) {
            String eachKeyword = _keywords[i];
            // 当keywords为空，初始化为默认值
            if (eachKeyword == null || eachKeyword.isEmpty()) {
                isKeywordPath[i] = false;
                keywords[i] = "";
                keywordsLowerCase[i] = "";
                continue;
            }
            final char _firstChar = eachKeyword.charAt(0);
            final boolean isPath = _firstChar == '/' || _firstChar == File.separatorChar;
            if (isPath) {
                // 当关键字为"test;/C:/test"时，分割出来为["test", "/C:/test"]，所以需要去掉 /C:/test 前面的 "/"
                if (eachKeyword.contains(":")) {
                    eachKeyword = eachKeyword.substring(1);
                }
                // 将 / 以及 \ 替换为空字符串，以便模糊匹配文件夹路径
                Matcher matcher = RegexUtil.getPattern("/|\\\\", 0).matcher(eachKeyword);
                eachKeyword = matcher.replaceAll(Matcher.quoteReplacement(""));
            }
            isKeywordPath[i] = isPath;
            keywords[i] = eachKeyword;
            keywordsLowerCase[i] = eachKeyword.toLowerCase();
        }
        return new SearchInfo(searchCase, isIgnoreCase, searchText, keywords, keywordsLowerCase, isKeywordPath);
    }

    @EventListener(listenClass = SearchBarReadyEvent.class)
    private static void searchBarVisibleListener(Event event) {
        SQLiteUtil.openAllConnection();
        getInstance().sendExecuteSQLSignal();
    }

    @EventRegister(registerClass = CheckDatabaseEmptyEvent.class)
    private static void checkDatabaseEmpty(Event event) {
        boolean databaseDamaged = SQLiteUtil.isDatabaseDamaged();
        event.setReturnValue(databaseDamaged);
    }

    @EventRegister(registerClass = InitializeDatabaseEvent.class)
    private static void initAllDatabases(Event event) {
        SQLiteUtil.initAllConnections();
    }

    @EventRegister(registerClass = StartMonitorDiskEvent.class)
    private static void startMonitorDiskEvent(Event event) {
        startMonitorDisk();
    }

    @EventListener(listenClass = SetConfigsEvent.class)
    private static void setGpuDevice(Event event) {
        var device = AllConfigs.getInstance().getConfigEntity().getGpuDevice();
        if (!GPUAccelerator.INSTANCE.setDevice(device)) {
            System.err.println("gpu设备" + device + "无效");
        }
    }

    @EventRegister(registerClass = PrepareSearchEvent.class)
    private static void prepareSearchEvent(Event event) {
        if (IsDebug.isDebug()) {
            System.out.println("进行预搜索并添加搜索任务");
        }
        var startWaiting = System.currentTimeMillis();
        final long timeout = 3000;
        var databaseService = getInstance();
        while (databaseService.getStatus() != Constants.Enums.DatabaseStatus.NORMAL) {
            if (System.currentTimeMillis() - startWaiting > timeout) {
                System.err.println("prepareSearch，等待数据库状态超时");
                break;
            }
            Thread.onSpinWait();
        }

        var prepareSearchEvent = (PrepareSearchEvent) event;
        var searchInfo = prepareSearchKeywords(prepareSearchEvent.searchText, prepareSearchEvent.searchCase, prepareSearchEvent.keywords);
        var searchTask = prepareSearch(searchInfo);
        prepareTasksMap.put(searchInfo, searchTask);
        event.setReturnValue(searchTask.tempResults);
    }

    @EventRegister(registerClass = StartSearchEvent.class)
    private static void startSearchEvent(Event event) {
        DatabaseService databaseService = getInstance();
        databaseService.stopAllSearch();
        final long startWaiting = System.currentTimeMillis();
        final long timeout = 3000;
        while (databaseService.getStatus() != Constants.Enums.DatabaseStatus.NORMAL ||
                databaseService.searchThreadCount.get() != 0) {
            if (System.currentTimeMillis() - startWaiting > timeout) {
                System.out.println("等待上次搜索结束超时");
                break;
            }
            Thread.onSpinWait();
        }

        var startSearchEvent = (StartSearchEvent) event;
        var searchInfo = prepareSearchKeywords(startSearchEvent.searchText, startSearchEvent.searchCase, startSearchEvent.keywords);
        SearchTask searchTask = null;
        if (prepareTasksMap.containsKey(searchInfo)) {
            searchTask = prepareTasksMap.get(searchInfo);
        }
        if (searchTask == null) {
            searchTask = prepareSearch(searchInfo);
        }
        searchTasksQueue.add(searchTask);
        // 检查prepareTaskMap中是否有过期任务
        final long maxTaskValidThreshold = 5000;
        for (var eachTask : prepareTasksMap.entrySet()) {
            if (System.currentTimeMillis() - eachTask.getValue().taskCreateTimeMills > maxTaskValidThreshold) {
                prepareTasksMap.remove(eachTask.getKey());
            }
        }
        event.setReturnValue(searchTask.tempResults);
    }

    /**
     * 防止PrepareSearchEvent未完成正在创建taskMap时，StartSearchEvent开始，导致两个线程同时添加任务（极端情况，一般不会出现）
     *
     * @param searchInfo searchInfo
     */
    private static synchronized SearchTask prepareSearch(SearchInfo searchInfo) {
        var databaseService = getInstance();
        var searchTask = new SearchTask(searchInfo);
        for (var eachPriority : databaseService.priorityMap) {
            searchTask.priorityContainer.put(String.valueOf(eachPriority.priority), new ConcurrentLinkedQueue<>());
        }

        var cachedThreadPoolUtil = CachedThreadPoolUtil.getInstance();
        databaseService.searchCache(searchTask);
        cachedThreadPoolUtil.executeTask(() -> {
            //noinspection ConstantConditions
            do {
                databaseService.searchPriorityFolder(searchTask);
                if (searchTask.shouldStopSearch.get()) {
                    break;
                }
                databaseService.searchStartMenu(searchTask);
                if (searchTask.shouldStopSearch.get()) {
                    break;
                }
                databaseService.searchDesktop(searchTask);
                if (searchTask.shouldStopSearch.get()) {
                    break;
                }
            } while (false);
        });
        databaseService.prepareSearchTasks(searchTask);
        if (isEnableGPUAccelerate && !searchTask.shouldStopSearch.get()) {
            cachedThreadPoolUtil.executeTask(() -> {
                // 退出上一次搜索
                final var timeout = 3000;
                GPUAccelerator.INSTANCE.stopCollectResults();
                final long start = System.currentTimeMillis();
                while (!SearchTask.isGpuThreadRunning.compareAndSet(false, true)) {
                    if (System.currentTimeMillis() - start > timeout) {
                        System.err.println("等待上一次gpu加速完成超时");
                        return;
                    }
                    if (searchTask.shouldStopSearch.get()) {
                        return;
                    }
                    Thread.onSpinWait();
                }
                // 开始进行搜索
                GPUAccelerator.INSTANCE.resetAllResultStatus();
                GPUAccelerator.INSTANCE.match(
                        searchInfo.searchCase,
                        searchInfo.isIgnoreCase,
                        searchInfo.searchText,
                        searchInfo.keywords,
                        searchInfo.keywordsLowerCase,
                        searchInfo.isKeywordPath,
                        MAX_RESULTS,
                        (key, path) -> {
                            if (searchTask.shouldStopSearch.get()) {
                                return;
                            }
                            if (!FileUtil.isFileExist(path)) {
                                databaseService.removeFileFromDatabase(path);
                                return;
                            }
                            if (searchTask.tempResultsForEvent.add(path)) {
                                var priorityStr = RegexUtil.comma.split(key)[2];
                                searchTask.priorityContainer.get(priorityStr).add(path);
                                if (searchTask.tempResultsForEvent.size() >= MAX_RESULTS) {
                                    searchTask.shouldStopSearch.set(true);
                                }
                            }
                        });
                SearchTask.isGpuThreadRunning.set(false);
            }, false);
        }
        return searchTask;
    }

    @EventRegister(registerClass = StopSearchEvent.class)
    private static void stopSearchEvent(Event event) {
        DatabaseService databaseService = getInstance();
        databaseService.stopAllSearch();
    }

    @EventListener(listenClass = BootSystemEvent.class)
    private static void databaseServiceInit(Event event) {
        DatabaseService databaseService = getInstance();
        databaseService.initPriority();
        databaseService.initTableMap();
        databaseService.prepareDatabaseCache();
        var allConfigs = AllConfigs.getInstance();
        for (String diskPath : RegexUtil.comma.split(allConfigs.getAvailableDisks())) {
            for (int i = 0; i <= Constants.ALL_TABLE_NUM; i++) {
                for (SuffixPriorityPair suffixPriorityPair : databaseService.priorityMap) {
                    databaseService.tableCache.put(diskPath.charAt(0) + "," + "list" + i + "," + suffixPriorityPair.priority, new Cache());
                }
            }
        }
        databaseService.syncFileChangesThread();
        databaseService.checkTimeAndSendExecuteSqlSignalThread();
        databaseService.executeSqlCommandsThread();
        databaseService.saveTableCacheThread();
        databaseService.startSearchThread();
        isEnableGPUAccelerate = allConfigs.getConfigEntity().isEnableGpuAccelerate();
    }

    @EventRegister(registerClass = AddToCacheEvent.class)
    private static void addToCacheEvent(Event event) {
        DatabaseService databaseService = getInstance();
        String path = ((AddToCacheEvent) event).path;
        databaseService.databaseCacheSet.add(path);
        if (databaseService.status.get() == Constants.Enums.DatabaseStatus._TEMP) {
            return;
        }
        databaseService.addFileToCache(path);
    }

    @EventRegister(registerClass = DeleteFromCacheEvent.class)
    private static void deleteFromCacheEvent(Event event) {
        DatabaseService databaseService = getInstance();
        String path = ((DeleteFromCacheEvent) event).path;
        databaseService.databaseCacheSet.remove(path);
        if (databaseService.status.get() == Constants.Enums.DatabaseStatus._TEMP) {
            return;
        }
        databaseService.removeFileFromCache(path);
    }

    @EventRegister(registerClass = UpdateDatabaseEvent.class)
    private static void updateDatabaseEvent(Event event) {
        DatabaseService databaseService = getInstance();
        UpdateDatabaseEvent updateDatabaseEvent = (UpdateDatabaseEvent) event;
        // 在这里设置数据库状态为manual update
        try {
            if (!databaseService.updateLists(AllConfigs.getInstance().getConfigEntity().getIgnorePath(), updateDatabaseEvent.isDropPrevious)) {
                throw new RuntimeException("search failed");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @EventRegister(registerClass = OptimiseDatabaseEvent.class)
    private static void optimiseDatabaseEvent(Event event) {
        DatabaseService databaseService = getInstance();
        if (databaseService.status.get() == Constants.Enums.DatabaseStatus._TEMP) {
            return;
        }
        if (databaseService.casSetStatus(Constants.Enums.DatabaseStatus.NORMAL, Constants.Enums.DatabaseStatus.VACUUM)) {
            throw new RuntimeException("databaseService status设置VACUUM状态失败");
        }
        //执行VACUUM命令
        for (String eachDisk : RegexUtil.comma.split(AllConfigs.getInstance().getAvailableDisks())) {
            try (Statement stmt = SQLiteUtil.getStatement(String.valueOf(eachDisk.charAt(0)))) {
                stmt.execute("VACUUM;");
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                if (IsDebug.isDebug()) {
                    System.out.println("结束优化");
                }
            }
        }
        if (databaseService.casSetStatus(Constants.Enums.DatabaseStatus.VACUUM, Constants.Enums.DatabaseStatus.NORMAL)) {
            throw new RuntimeException("databaseService status从VACUUM修改为NORMAL失败");
        }
    }

    @EventRegister(registerClass = AddToSuffixPriorityMapEvent.class)
    private static void addToSuffixPriorityMapEvent(Event event) {
        DatabaseService databaseService = getInstance();
        if (databaseService.status.get() == Constants.Enums.DatabaseStatus._TEMP) {
            return;
        }
        AddToSuffixPriorityMapEvent event1 = (AddToSuffixPriorityMapEvent) event;
        String suffix = event1.suffix;
        int priority = event1.priority;
        databaseService.addToCommandQueue(
                new SQLWithTaskId(String.format("INSERT INTO priority VALUES(\"%s\", %d);", suffix, priority), SqlTaskIds.UPDATE_SUFFIX, "cache"));
    }

    @EventRegister(registerClass = ClearSuffixPriorityMapEvent.class)
    private static void clearSuffixPriorityMapEvent(Event event) {
        DatabaseService databaseService = getInstance();
        if (databaseService.status.get() == Constants.Enums.DatabaseStatus._TEMP) {
            return;
        }
        databaseService.addToCommandQueue(new SQLWithTaskId("DELETE FROM priority;", SqlTaskIds.UPDATE_SUFFIX, "cache"));
        databaseService.addToCommandQueue(
                new SQLWithTaskId("INSERT INTO priority VALUES(\"defaultPriority\", 0);", SqlTaskIds.UPDATE_SUFFIX, "cache"));
        databaseService.addToCommandQueue(
                new SQLWithTaskId("INSERT INTO priority VALUES(\"dirPriority\", -1);", SqlTaskIds.UPDATE_SUFFIX, "cache"));
    }

    @EventRegister(registerClass = DeleteFromSuffixPriorityMapEvent.class)
    private static void deleteFromSuffixPriorityMapEvent(Event event) {
        DeleteFromSuffixPriorityMapEvent delete = (DeleteFromSuffixPriorityMapEvent) event;
        DatabaseService databaseService = getInstance();
        if (databaseService.status.get() == Constants.Enums.DatabaseStatus._TEMP) {
            return;
        }
        if ("dirPriority".equals(delete.suffix) || "defaultPriority".equals(delete.suffix)) {
            return;
        }
        databaseService.addToCommandQueue(new SQLWithTaskId(String.format("DELETE FROM priority where SUFFIX=\"%s\"", delete.suffix), SqlTaskIds.UPDATE_SUFFIX, "cache"));
    }

    @EventRegister(registerClass = UpdateSuffixPriorityEvent.class)
    private static void updateSuffixPriorityEvent(Event event) {
        DatabaseService databaseService = DatabaseService.getInstance();
        if (databaseService.status.get() == Constants.Enums.DatabaseStatus._TEMP) {
            return;
        }
        EventManagement eventManagement = EventManagement.getInstance();
        UpdateSuffixPriorityEvent update = (UpdateSuffixPriorityEvent) event;
        String origin = update.originSuffix;
        String newSuffix = update.newSuffix;
        int newNum = update.newPriority;
        eventManagement.putEvent(new DeleteFromSuffixPriorityMapEvent(origin));
        eventManagement.putEvent(new AddToSuffixPriorityMapEvent(newSuffix, newNum));
    }

    @EventListener(listenClass = RestartEvent.class)
    private static void restartEvent(Event event) {
        FileMonitor.INSTANCE.stop_monitor();
        DatabaseService databaseService = getInstance();
        databaseService.executeAllCommands();
        databaseService.stopAllSearch();
        SQLiteUtil.closeAll();
        if (isEnableGPUAccelerate) {
            GPUAccelerator.INSTANCE.stopCollectResults();
            GPUAccelerator.INSTANCE.release();
        }
    }

    @Data
    @EqualsAndHashCode
    private static class SQLWithTaskId {
        private final String sql;
        private final SqlTaskIds taskId;
        private final String diskStr;
        private volatile String key;
    }

    private enum SqlTaskIds {
        DELETE_FROM_LIST, DELETE_FROM_CACHE, INSERT_TO_LIST, INSERT_TO_CACHE,
        CREATE_INDEX, CREATE_TABLE, DROP_TABLE, DROP_INDEX, UPDATE_SUFFIX, UPDATE_WEIGHT
    }

    @SuppressWarnings("unused")
    private static class GPUCacheService {
        private static final ConcurrentLinkedQueue<String> invalidCacheKeys = new ConcurrentLinkedQueue<>();
        private static final ConcurrentHashMap<String, Set<String>> recordsToAdd = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Set<String>> recordsToRemove = new ConcurrentHashMap<>();

        private static void clearInvalidCacheThread() {
            //检测缓存是否有效并删除缓存
            CachedThreadPoolUtil.getInstance().executeTask(() -> {
                EventManagement eventManagement = EventManagement.getInstance();
                DatabaseService databaseService = DatabaseService.getInstance();
                long startCheckInvalidCacheTime = System.currentTimeMillis();
                final long checkInterval = 10 * 60 * 1000; // 10min
                while (eventManagement.notMainExit()) {
                    if (System.currentTimeMillis() - startCheckInvalidCacheTime > checkInterval && !GetHandle.INSTANCE.isForegroundFullscreen()) {
                        startCheckInvalidCacheTime = System.currentTimeMillis();
                        String eachKey;
                        while (eventManagement.notMainExit() && (eachKey = invalidCacheKeys.poll()) != null) {
                            GPUAccelerator.INSTANCE.clearCache(eachKey);
                        }
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

            });
        }

        private static void execWorkQueueThread() {
            CachedThreadPoolUtil.getInstance().executeTask(() -> {
                EventManagement eventManagement = EventManagement.getInstance();
                DatabaseService databaseService = DatabaseService.getInstance();
                final int removeRecordsThreshold = 20;
                while (eventManagement.notMainExit()) {
                    if (databaseService.getStatus() == Constants.Enums.DatabaseStatus.NORMAL &&
                            !GetHandle.INSTANCE.isForegroundFullscreen() &&
                            (!recordsToAdd.isEmpty() || !recordsToRemove.isEmpty())) {
                        for (var entry : recordsToAdd.entrySet()) {
                            String k = entry.getKey();
                            Set<String> container = entry.getValue();
                            if (container.isEmpty()) continue;
                            var records = container.toArray();
                            GPUAccelerator.INSTANCE.addRecordsToCache(k, records);
                            for (Object record : records) {
                                container.remove((String) record);
                            }
                        }
                        for (var entry : recordsToRemove.entrySet()) {
                            String key = entry.getKey();
                            Set<String> container = entry.getValue();
                            if (container.size() < removeRecordsThreshold) continue;
                            var records = container.toArray();
                            GPUAccelerator.INSTANCE.removeRecordsFromCache(key, records);
                            for (Object record : records) {
                                container.remove((String) record);
                            }
                        }
                    }
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        private static void addRecord(String key, String fileRecord) {
            if (GPUAccelerator.INSTANCE.isCacheExist(key) && !GPUAccelerator.INSTANCE.isCacheValid(key)) {
                invalidCacheKeys.add(key);
            } else {
                Set<String> container;
                if (recordsToAdd.containsKey(key)) {
                    container = recordsToAdd.get(key);
                    container.add(fileRecord);
                } else {
                    container = ConcurrentHashMap.newKeySet();
                    container.add(fileRecord);
                    recordsToAdd.put(key, container);
                }
            }
        }

        private static void removeRecord(String key, String fileRecord) {
            Set<String> container;
            if (recordsToRemove.containsKey(key)) {
                container = recordsToRemove.get(key);
                container.add(fileRecord);
            } else {
                container = ConcurrentHashMap.newKeySet();
                container.add(fileRecord);
                recordsToRemove.put(key, container);
            }
        }

        @EventListener(listenClass = BootSystemEvent.class)
        private static void startThread(Event event) {
            if (!isEnableGPUAccelerate) {
                return;
            }
            clearInvalidCacheThread();
            //向gpu缓存添加或删除记录线程
            execWorkQueueThread();
        }

        @EventRegister(registerClass = GPUAddRecordEvent.class)
        private static void addToGPUMemory(Event event) {
            if (!isEnableGPUAccelerate) {
                return;
            }
            GPUAddRecordEvent gpuAddRecordEvent = (GPUAddRecordEvent) event;
            addRecord(gpuAddRecordEvent.key, gpuAddRecordEvent.record);
        }

        @EventRegister(registerClass = GPURemoveRecordEvent.class)
        private static void removeFromGPUMemory(Event event) {
            if (!isEnableGPUAccelerate) {
                return;
            }
            GPURemoveRecordEvent gpuRemoveRecordEvent = (GPURemoveRecordEvent) event;
            removeRecord(gpuRemoveRecordEvent.key, gpuRemoveRecordEvent.record);
        }

        @EventRegister(registerClass = IsCacheExistEvent.class)
        private static void isCacheExistEvent(Event event) {
            IsCacheExistEvent cacheExist = (IsCacheExistEvent) event;
            event.setReturnValue(getInstance().databaseCacheSet.contains(cacheExist.cache));
        }

        @EventRegister(registerClass = GPUClearCacheEvent.class)
        private static void clearCacheGPU(Event event) {
            if (!isEnableGPUAccelerate) {
                return;
            }
            GPUAccelerator.INSTANCE.clearAllCache();
        }
    }

    private record SuffixPriorityPair(String suffix, int priority) {
    }

    /**
     * 内存缓存包装类
     * 数据库缓存分为GPU加速缓存和内存缓存
     * 当GPU加速不可用或者关闭时，将会启用内存缓存
     */
    private static class Cache {
        private final AtomicBoolean isCached = new AtomicBoolean(false);
        private final AtomicBoolean isFileLost = new AtomicBoolean(false);
        private ConcurrentLinkedQueue<String> data = null;

        private boolean isCacheValid() {
            return isCached.get() && !isFileLost.get();
        }
    }

    private static class TableNameWeightInfo {
        private final String tableName;
        private final AtomicLong weight;

        private TableNameWeightInfo(String tableName, int weight) {
            this.tableName = tableName;
            this.weight = new AtomicLong(weight);
        }
    }

    /**
     * 搜索任务封装
     * 根据list0-40，以及后缀优先级生成任务，放入taskMap中，key为磁盘盘符，value为搜索任务
     * 在收到startSearchEvent之后将会遍历taskMap执行搜索任务
     * @see #startSearch(SearchTask)
     *
     * 搜索结果将会被暂存到priorityContainer中，key为后缀优先级，value为该后缀的文件路径，同时也会存入tempResultsForEvent中用于去重以及发送事件
     * 在等待搜索完成时，priorityContainer中的数据会被不断转存到tempResults中，按照后缀优先级降序排列，优先级高的文件将会先转存
     * <p>
     * taskStatus和allTaskStatus是每个任务的标志，每个任务分配一个位，当某一个任务完成，在taskStatus上该任务的位将会被设置为1
     * 当taskstauts和allTaskStatus相等则表示任务全部完成
     * @see #waitForTasks(SearchTask)
     */
    @RequiredArgsConstructor
    private static class SearchTask {
        //taskMap任务队列，key为磁盘盘符，value为任务
        private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Runnable>> taskMap = new ConcurrentHashMap<>();
        private final Bit taskStatus = new Bit(new byte[]{0});
        private final Bit allTaskStatus = new Bit(new byte[]{0});
        private final SearchInfo searchInfo;
        private final ConcurrentLinkedQueue<String> tempResults = new ConcurrentLinkedQueue<>();
        private final Set<String> tempResultsForEvent = new ConcurrentSkipListSet<>(); //在SearchDoneEvent中保存的容器
        private final AtomicBoolean shouldStopSearch = new AtomicBoolean();
        private final long taskCreateTimeMills = System.currentTimeMillis();
        private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> priorityContainer = new ConcurrentHashMap<>();

        private static final AtomicBoolean isGpuThreadRunning = new AtomicBoolean();
    }

    /**
     * 因为拥有String[]以及boolean[]数组，所以转换成Record将会导致Equals和Hashcode出现问题
     * 导致两个内容相同的SearchInfo不相等，不能转换成record
     */
    @EqualsAndHashCode
    @RequiredArgsConstructor
    private static class SearchInfo {
        private final String[] searchCase;
        private final boolean isIgnoreCase;
        private final String searchText;
        private final String[] keywords;
        private final String[] keywordsLowerCase;
        private final boolean[] isKeywordPath;
    }
}

