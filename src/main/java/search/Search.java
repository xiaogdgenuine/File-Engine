package search;

import com.sun.jna.WString;
import everythingSDK.Everything;
import frames.SearchBar;
import frames.SettingsFrame;
import main.MainClass;

import javax.swing.filechooser.FileSystemView;
import java.io.*;
import java.nio.Buffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CopyOnWriteArraySet;


public class Search {
    private static boolean isUsable = true;
    private static boolean isManualUpdate = false;
    private static Search searchInstance = new Search();
    private CopyOnWriteArraySet<String> RecycleBin = new CopyOnWriteArraySet<>();
    private CopyOnWriteArraySet<String> listToLoad = new CopyOnWriteArraySet<>();

    private Search() {
    }

    public static Search getInstance() {
        return searchInstance;
    }

    private static void writeRecordToFile(String record, String srcPath) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(srcPath, true))) {
            bw.write(record);
            bw.write("\n");
        } catch (IOException ignored) {

        }
    }

    public int getRecycleBinSize() {
        return RecycleBin.size();
    }

    public int getLoadListSize() {
        return listToLoad.size();
    }

    public void addToRecycleBin(String path) {
        RecycleBin.add(path);
    }

    public void mergeAndClearRecycleBin() {
        if (!isManualUpdate) {
            isUsable = false;
            CopyOnWriteArraySet<String> set0 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set100 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set200 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set300 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set400 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set500 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set600 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set700 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set800 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set900 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1000 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1100 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1200 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1300 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1400 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1500 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1600 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1700 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1800 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set1900 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set2000 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set2100 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set2200 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set2300 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set2400 = new CopyOnWriteArraySet<>();
            CopyOnWriteArraySet<String> set2500 = new CopyOnWriteArraySet<>();
            try { //垃圾分类
                for (String i : RecycleBin) {
                    SearchBar instance = SearchBar.getInstance();
                    int ascII = instance.getAscIISum(instance.getFileName(i));
                    if (0 < ascII && ascII < 100) {
                        set0.add(i);
                    } else if (100 < ascII && ascII <= 200) {
                        set100.add(i);
                    } else if (200 < ascII && ascII <= 300) {
                        set200.add(i);
                    } else if (300 < ascII && ascII <= 400) {
                        set300.add(i);
                    } else if (400 < ascII && ascII <= 500) {
                        set400.add(i);
                    } else if (500 < ascII && ascII <= 600) {
                        set500.add(i);
                    } else if (600 < ascII && ascII <= 700) {
                        set600.add(i);
                    } else if (700 < ascII && ascII <= 800) {
                        set700.add(i);
                    } else if (800 < ascII && ascII <= 900) {
                        set800.add(i);
                    } else if (900 < ascII && ascII <= 1000) {
                        set900.add(i);
                    } else if (1000 < ascII && ascII <= 1100) {
                        set1000.add(i);
                    } else if (1100 < ascII && ascII <= 1200) {
                        set1100.add(i);
                    } else if (1200 < ascII && ascII <= 1300) {
                        set1200.add(i);
                    } else if (1300 < ascII && ascII <= 1400) {
                        set1300.add(i);
                    } else if (1400 < ascII && ascII <= 1500) {
                        set1400.add(i);
                    } else if (1500 < ascII && ascII <= 1600) {
                        set1500.add(i);
                    } else if (1600 < ascII && ascII <= 1700) {
                        set1600.add(i);
                    } else if (1700 < ascII && ascII <= 1800) {
                        set1700.add(i);
                    } else if (1800 < ascII && ascII <= 1900) {
                        set1800.add(i);
                    } else if (1900 < ascII && ascII <= 2000) {
                        set1900.add(i);
                    } else if (2000 < ascII && ascII <= 2100) {
                        set2000.add(i);
                    } else if (2100 < ascII && ascII <= 2200) {
                        set2100.add(i);
                    } else if (2200 < ascII && ascII <= 2300) {
                        set2200.add(i);
                    } else if (2300 < ascII && ascII <= 2400) {
                        set2300.add(i);
                    } else if (2400 < ascII && ascII <= 2500) {
                        set2400.add(i);
                    } else {
                        set2500.add(i);
                    }
                }
                RecycleBin.clear();
                String srcPath;
                String destPath;
                File src;
                File target;

                if (!set0.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list0-100.txt";
                    destPath = SettingsFrame.dataPath + "\\_list0-100.txt";
                    deleteRecordInFile(set0, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set100.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list100-200.txt";
                    destPath = SettingsFrame.dataPath + "\\_list100-200.txt";
                    deleteRecordInFile(set100, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set200.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list200-300.txt";
                    destPath = SettingsFrame.dataPath + "\\_list200-300.txt";
                    deleteRecordInFile(set200, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set300.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list300-400.txt";
                    destPath = SettingsFrame.dataPath + "\\_list300-400.txt";
                    deleteRecordInFile(set300, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set400.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list400-500.txt";
                    destPath = SettingsFrame.dataPath + "\\_list400-500.txt";
                    deleteRecordInFile(set400, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set500.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list500-600.txt";
                    destPath = SettingsFrame.dataPath + "\\_list500-600.txt";
                    deleteRecordInFile(set500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set600.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list600-700.txt";
                    destPath = SettingsFrame.dataPath + "\\_list600-700.txt";
                    deleteRecordInFile(set600, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set700.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list700-800.txt";
                    destPath = SettingsFrame.dataPath + "\\_list700-800.txt";
                    deleteRecordInFile(set700, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set800.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list800-900.txt";
                    destPath = SettingsFrame.dataPath + "\\_list800-900.txt";
                    deleteRecordInFile(set800, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set900.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list900-1000.txt";
                    destPath = SettingsFrame.dataPath + "\\_list900-1000.txt";
                    deleteRecordInFile(set900, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1000.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1000-1100.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1000-1100.txt";
                    deleteRecordInFile(set1000, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1100.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1100-1200.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1100-1200.txt";
                    deleteRecordInFile(set1100, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1200.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1200-1300.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1200-1300.txt";
                    deleteRecordInFile(set1200, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1300.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1300-1400.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1300-1400.txt";
                    deleteRecordInFile(set1300, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1400.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1400-1500.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1400-1500.txt";
                    deleteRecordInFile(set1400, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1500.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1500-1600.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1500-1600.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1600.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1600-1700.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1600-1700.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1700.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1700-1800.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1700-1800.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1800.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1800-1900.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1800-1900.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set1900.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list1900-2000.txt";
                    destPath = SettingsFrame.dataPath + "\\_list1900-2000.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set2000.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list2000-2100.txt";
                    destPath = SettingsFrame.dataPath + "\\_list2000-2100.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set2100.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list2100-2200.txt";
                    destPath = SettingsFrame.dataPath + "\\_list2100-2200.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set2200.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list2200-2300.txt";
                    destPath = SettingsFrame.dataPath + "\\_list2200-2300.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set2300.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list2300-2400.txt";
                    destPath = SettingsFrame.dataPath + "\\_list2300-2400.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set2400.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list2400-2500.txt";
                    destPath = SettingsFrame.dataPath + "\\_list2400-2500.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }
                if (!set2500.isEmpty()) {
                    srcPath = SettingsFrame.dataPath + "\\list2500-.txt";
                    destPath = SettingsFrame.dataPath + "\\_list2500-.txt";
                    deleteRecordInFile(set1500, srcPath, destPath);
                    src = new File(srcPath);
                    src.delete();
                    target = new File(destPath);
                    target.renameTo(src);
                }

            } catch (ConcurrentModificationException ignored) {

            } finally {
                isUsable = true;
            }
        }
    }

    private void deleteRecordInFile(CopyOnWriteArraySet<String> recordToDel, String srcText, String destText) {
        if (!recordToDel.isEmpty()) {
            try (BufferedReader br = new BufferedReader(new FileReader(srcText)); BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(destText, true)))) {
                String eachLine;
                while ((eachLine = br.readLine()) != null) {
                    if (!recordToDel.contains(eachLine)) {
                        bw.write(eachLine);
                        bw.write("\n");
                    }
                }
            } catch (IOException ignored) {

            }
        }
    }

    public void addFileToLoadBin(String path) {
        listToLoad.add(path);
    }

    public void mergeFileToList() {
        if (!isManualUpdate) {
            isUsable = false;
            for (String each : listToLoad) {
                File add = new File(each);
                if (add.exists()) {
                    addRecordToLocal(each);
                }
            }
            isUsable = true;
            listToLoad.clear();
        }
    }

    public boolean isManualUpdate() {
        return isManualUpdate;
    }

    public void setManualUpdate(boolean b) {
        isManualUpdate = b;
    }

    public boolean isUsable() {
        return isUsable;
    }

    public void setUsable(boolean b) {
        if (!isManualUpdate) {
            isUsable = b;
        } else {
            isUsable = false;
        }
    }

    private void addRecordToLocal(String path) {
        File file = new File(path);
        int ascII = SearchBar.getInstance().getAscIISum(file.getName());
        String listPath;
        if (0 < ascII && ascII <= 100) {
            listPath = SettingsFrame.dataPath + "\\list0-100.txt";
            writeRecordToFile(path, listPath);
        } else if (100 < ascII && ascII <= 200) {
            listPath = SettingsFrame.dataPath + "\\list100-200.txt";
            writeRecordToFile(path, listPath);
        } else if (200 < ascII && ascII <= 300) {
            listPath = SettingsFrame.dataPath + "\\list200-300.txt";
            writeRecordToFile(path, listPath);
        } else if (300 < ascII && ascII <= 400) {
            listPath = SettingsFrame.dataPath + "\\list300-400.txt";

            writeRecordToFile(path, listPath);
        } else if (400 < ascII && ascII <= 500) {
            listPath = SettingsFrame.dataPath + "\\list400-500.txt";

            writeRecordToFile(path, listPath);
        } else if (500 < ascII && ascII <= 600) {
            listPath = SettingsFrame.dataPath + "\\list500-600.txt";

            writeRecordToFile(path, listPath);
        } else if (600 < ascII && ascII <= 700) {
            listPath = SettingsFrame.dataPath + "\\list600-700.txt";
            writeRecordToFile(path, listPath);
        } else if (700 < ascII && ascII <= 800) {
            listPath = SettingsFrame.dataPath + "\\list700-800.txt";

            writeRecordToFile(path, listPath);
        } else if (800 < ascII && ascII <= 900) {
            listPath = SettingsFrame.dataPath + "\\list800-900.txt";

            writeRecordToFile(path, listPath);
        } else if (900 < ascII && ascII <= 1000) {
            listPath = SettingsFrame.dataPath + "\\list900-1000.txt";
            writeRecordToFile(path, listPath);
        } else if (1000 < ascII && ascII <= 1100) {
            listPath = SettingsFrame.dataPath + "\\list1000-1100.txt";

            writeRecordToFile(path, listPath);
        } else if (1100 < ascII && ascII <= 1200) {
            listPath = SettingsFrame.dataPath + "\\list1100-1200.txt";
            writeRecordToFile(path, listPath);
        } else if (1200 < ascII && ascII <= 1300) {
            listPath = SettingsFrame.dataPath + "\\list1200-1300.txt";
            writeRecordToFile(path, listPath);
        } else if (1300 < ascII && ascII <= 1400) {
            listPath = SettingsFrame.dataPath + "\\list1300-1400.txt";

            writeRecordToFile(path, listPath);
        } else if (1400 < ascII && ascII <= 1500) {
            listPath = SettingsFrame.dataPath + "\\list1400-1500.txt";

            writeRecordToFile(path, listPath);
        } else if (1500 < ascII && ascII <= 1600) {
            listPath = SettingsFrame.dataPath + "\\list1500-1600.txt";

            writeRecordToFile(path, listPath);
        } else if (1600 < ascII && ascII <= 1700) {
            listPath = SettingsFrame.dataPath + "\\list1600-1700.txt";

            writeRecordToFile(path, listPath);
        } else if (1700 < ascII && ascII <= 1800) {
            listPath = SettingsFrame.dataPath + "\\list1700-1800.txt";

            writeRecordToFile(path, listPath);
        } else if (1800 < ascII && ascII <= 1900) {
            listPath = SettingsFrame.dataPath + "\\list1800-1900.txt";

            writeRecordToFile(path, listPath);
        } else if (1900 < ascII && ascII <= 2000) {
            listPath = SettingsFrame.dataPath + "\\list1900-2000.txt";

            writeRecordToFile(path, listPath);
        } else if (2000 < ascII && ascII <= 2100) {
            listPath = SettingsFrame.dataPath + "\\list2000-2100.txt";

            writeRecordToFile(path, listPath);
        } else if (2100 < ascII && ascII <= 2200) {
            listPath = SettingsFrame.dataPath + "\\list2100-2200.txt";

            writeRecordToFile(path, listPath);
        } else if (2200 < ascII && ascII <= 2300) {
            listPath = SettingsFrame.dataPath + "\\list2200-2300.txt";

            writeRecordToFile(path, listPath);
        } else if (2300 < ascII && ascII <= 2400) {
            listPath = SettingsFrame.dataPath + "\\list2300-2400.txt";

            writeRecordToFile(path, listPath);
        } else if (2400 < ascII && ascII <= 2500) {
            listPath = SettingsFrame.dataPath + "\\list2400-2500.txt";

            writeRecordToFile(path, listPath);
        } else {
            listPath = SettingsFrame.dataPath + "\\list2500-.txt";
            writeRecordToFile(path, listPath);
        }

        if (!isManualUpdate) {
            isUsable = true;
        }
    }


    private void searchFile(String ignorePath, int searchDepth) {
        //TODO 检测Everything
        if (isEverythingRunning()) {
            Everything.INSTANCE.Everything_SetSearchW(new WString(""));
            Everything.INSTANCE.Everything_QueryW(true);
            int all = Everything.INSTANCE.Everything_GetTotResults();
            Buffer result = CharBuffer.allocate(1000);
            try (BufferedWriter bw0 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list0-100.txt", true)));
                 BufferedWriter bw1 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list100-200.txt", true)));
                 BufferedWriter bw2 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list200-300.txt", true)));
                 BufferedWriter bw3 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list300-400.txt", true)));
                 BufferedWriter bw4 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list400-500.txt", true)));
                 BufferedWriter bw5 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list500-600.txt", true)));
                 BufferedWriter bw6 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list600-700.txt", true)));
                 BufferedWriter bw7 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list700-800.txt", true)));
                 BufferedWriter bw8 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list800-900.txt", true)));
                 BufferedWriter bw9 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list900-1000.txt", true)));
                 BufferedWriter bw10 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1000-1100.txt", true)));
                 BufferedWriter bw11 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1100-1200.txt", true)));
                 BufferedWriter bw12 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1200-1300.txt", true)));
                 BufferedWriter bw13 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1300-1400.txt", true)));
                 BufferedWriter bw14 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1400-1500.txt", true)));
                 BufferedWriter bw15 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1500-1600.txt", true)));
                 BufferedWriter bw16 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1600-1700.txt", true)));
                 BufferedWriter bw17 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1700-1800.txt", true)));
                 BufferedWriter bw18 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1800-1900.txt", true)));
                 BufferedWriter bw19 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list1900-2000.txt", true)));
                 BufferedWriter bw20 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list2000-2100.txt", true)));
                 BufferedWriter bw21 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list2100-2200.txt", true)));
                 BufferedWriter bw22 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list2200-2300.txt", true)));
                 BufferedWriter bw23 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list2300-2400.txt", true)));
                 BufferedWriter bw24 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list2400-2500.txt", true)));
                 BufferedWriter bw25 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(SettingsFrame.dataPath + "\\list2500-.txt", true)))) {
                char[] buf;
                String each;
                int ascII;
                for (int j = 0; j < all; j++) {
                    Everything.INSTANCE.Everything_GetResultFullPathNameW(j, result, all);
                    buf = (char[]) result.array();
                    each = new String(buf).trim();
                    int index = each.indexOf('\u0000');
                    if (index != -1) {
                        each = each.substring(0, index);
                    }
                    ascII = SearchBar.getInstance().getAscIISum(new File(each).getName());
                    if (0 < ascII && ascII <= 100) {
                        bw0.write(each + "\n");
                    } else if (100 < ascII && ascII <= 200) {
                        bw1.write(each + "\n");
                    } else if (200 < ascII && ascII <= 300) {
                        bw2.write(each + "\n");
                    } else if (300 < ascII && ascII <= 400) {
                        bw3.write(each + "\n");
                    } else if (400 < ascII && ascII <= 500) {
                        bw4.write(each + "\n");
                    } else if (500 < ascII && ascII <= 600) {
                        bw5.write(each + "\n");
                    } else if (600 < ascII && ascII <= 700) {
                        bw6.write(each + "\n");
                    } else if (700 < ascII && ascII <= 800) {
                        bw7.write(each + "\n");
                    } else if (800 < ascII && ascII <= 900) {

                        bw8.write(each + "\n");
                    } else if (900 < ascII && ascII <= 1000) {

                        bw9.write(each + "\n");
                    } else if (1000 < ascII && ascII <= 1100) {

                        bw10.write(each + "\n");
                    } else if (1100 < ascII && ascII <= 1200) {

                        bw11.write(each + "\n");
                    } else if (1200 < ascII && ascII <= 1300) {

                        bw12.write(each + "\n");
                    } else if (1300 < ascII && ascII <= 1400) {

                        bw13.write(each + "\n");
                    } else if (1400 < ascII && ascII <= 1500) {

                        bw14.write(each + "\n");
                    } else if (1500 < ascII && ascII <= 1600) {

                        bw15.write(each + "\n");
                    } else if (1600 < ascII && ascII <= 1700) {

                        bw16.write(each + "\n");
                    } else if (1700 < ascII && ascII <= 1800) {

                        bw17.write(each + "\n");
                    } else if (1800 < ascII && ascII <= 1900) {

                        bw18.write(each + "\n");
                    } else if (1900 < ascII && ascII <= 2000) {

                        bw19.write(each + "\n");
                    } else if (2000 < ascII && ascII <= 2100) {

                        bw20.write(each + "\n");
                    } else if (2100 < ascII && ascII <= 2200) {

                        bw21.write(each + "\n");
                    } else if (2200 < ascII && ascII <= 2300) {

                        bw22.write(each + "\n");
                    } else if (2300 < ascII && ascII <= 2400) {
                        bw23.write(each + "\n");
                    } else {
                        if (2400 < ascII && ascII <= 2500) {
                            bw24.write(each + "\n");
                        } else {
                            bw25.write(each + "\n");
                        }
                    }
                }
            } catch (IOException ignored) {

            }
        } else {
            File[] roots = File.listRoots();
            FileSystemView sys = FileSystemView.getFileSystemView();
            for (File root : roots) {
                String dirveType = sys.getSystemTypeDescription(root);
                if (dirveType.equals("本地磁盘")) {
                    String path = root.getAbsolutePath();
                    path = path.substring(0, 2);
                    __searchFile(path, searchDepth, ignorePath);
                }
            }
            __searchFileIgnoreSearchDepth(getStartMenu(), ignorePath);
            __searchFileIgnoreSearchDepth("C:\\ProgramData\\Microsoft\\Windows\\Start Menu", ignorePath);
        }
        MainClass.showMessage("提示", "搜索完成");
        isManualUpdate = false;
        isUsable = true;
    }

    private String getStartMenu() {
        String startMenu;
        BufferedReader bufrIn;
        try {
            Process getStartMenu = Runtime.getRuntime().exec("reg query \"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders\" " + "/v " + "\"Start Menu\"");
            bufrIn = new BufferedReader(new InputStreamReader(getStartMenu.getInputStream(), StandardCharsets.UTF_8));
            while ((startMenu = bufrIn.readLine()) != null) {
                if (startMenu.contains("REG_SZ")) {
                    startMenu = startMenu.substring(startMenu.indexOf("REG_SZ") + 10);
                    return startMenu;
                }
            }
        } catch (IOException e) {

            e.printStackTrace();
        }
        return null;
    }

    private void __searchFileIgnoreSearchDepth(String path, String ignorePath) {
        File fileSearcher = new File("./user/fileSearcher.exe");
        String absPath = fileSearcher.getAbsolutePath();
        String start = absPath.substring(0, 2);
        String end = "\"" + absPath.substring(2) + "\"";
        String command = "cmd /c " + start + end + " \"" + path + "\"" + " \"6\" " + "\"" + ignorePath + "\" " + "\"" + SettingsFrame.dataPath + "\" " + "\"" + "1" + "\"";
        Process p;
        try {
            p = Runtime.getRuntime().exec(command);
            while (p.isAlive()) {
                Thread.sleep(1);
            }
        } catch (IOException | InterruptedException ignored) {

        }
    }

    private void __searchFile(String path, int searchDepth, String ignorePath) {
        File fileSearcher = new File("./user/fileSearcher.exe");
        String absPath = fileSearcher.getAbsolutePath();
        String start = absPath.substring(0, 2);
        String end = "\"" + absPath.substring(2) + "\"";
        String command = "cmd /c " + start + end + " \"" + path + "\"" + " \"" + searchDepth + "\" " + "\"" + ignorePath + "\" " + "\"" + SettingsFrame.dataPath + "\" " + "\"" + "0" + "\"";
        Process p;
        try {
            p = Runtime.getRuntime().exec(command);
            while (p.isAlive()) {
                Thread.sleep(1);
            }
        } catch (IOException | InterruptedException ignored) {

        }
    }

    private void initDataFiles() {
        try {
            File target = new File(SettingsFrame.dataPath + "\\list0-100.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list100-200.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list200-300.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list300-400.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list400-500.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list500-600.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list600-700.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list700-800.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list800-900.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list900-1000.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1000-1100.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1100-1200.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1200-1300.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1300-1400.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1400-1500.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1500-1600.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1600-1700.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1700-1800.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1800-1900.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list1900-2000.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list2100-2200.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list2300-2400.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list2400-2500.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
            target = new File(SettingsFrame.dataPath + "\\list2500-.txt");
            if (!target.exists()) {
                target.createNewFile();
            }
        } catch (Exception ignored) {

        }
    }


    public void updateLists(String ignorePath, int searchDepth) {
        MainClass.deleteDir(SettingsFrame.dataPath);
        initDataFiles();
        searchFile(ignorePath, searchDepth);
    }

    private boolean isEverythingRunning() {
        String cmd = "tasklist /fi \"" + "imagename eq " + "Everything.exe" + "\"";
        Runtime r = Runtime.getRuntime();
        Process p;
        BufferedReader br = null;
        try {
            p = r.exec(cmd);
            br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("Everything.exe")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}