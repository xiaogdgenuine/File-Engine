package main;

import com.alibaba.fastjson.JSONObject;
import fileMonitor.FileMonitor;
import frame.SearchBar;
import frame.SettingsFrame;
import frame.TaskBar;
import search.Search;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class MainClass {
	public static boolean mainExit = false;
	private static SearchBar searchBar = new SearchBar();
	private static Search search = new Search();
	private static File fileWatcherTXT = new File(SettingsFrame.tmp.getAbsolutePath()+ "\\fileMonitor.txt");
	public static String name = "search_x64.exe";		//TODO 修改版本

	public static void setMainExit(boolean b){
		mainExit = b;
	}
	private static TaskBar taskBar = null;
	public static void showMessage(String caption, String message){
		if (taskBar != null){
			taskBar.showMessage(caption, message);
		}
	}
	public static boolean isAdmin() {
		try {
			ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe");
			Process process = processBuilder.start();
			PrintStream printStream = new PrintStream(process.getOutputStream(), true);
			Scanner scanner = new Scanner(process.getInputStream());
			printStream.println("@echo off");
			printStream.println(">nul 2>&1 \"%SYSTEMROOT%\\system32\\cacls.exe\" \"%SYSTEMROOT%\\system32\\config\\system\"");
			printStream.println("echo %errorlevel%");

			boolean printedErrorlevel = false;
			while (true) {
				String nextLine = scanner.nextLine();
				if (printedErrorlevel) {
					int errorlevel = Integer.parseInt(nextLine);
					return errorlevel == 0;
				} else if (nextLine.equals("echo %errorlevel%")) {
					printedErrorlevel = true;
				}
			}
		} catch (IOException e) {
			return false;
		}
	}

	private static void copyFile(InputStream source, File dest) {
		try(OutputStream os = new FileOutputStream(dest);BufferedInputStream bis = new BufferedInputStream(source);BufferedOutputStream bos = new BufferedOutputStream(os)) {
			byte[]buffer = new byte[8192];
			int count = bis.read(buffer);
			while(count != -1){
				//使用缓冲流写数据
				bos.write(buffer,0,count);
				//刷新
				bos.flush();
				count = bis.read(buffer);
			}
		} catch (IOException ignored) {

		}
	}

	public static void deleteDir(String path){
		File file = new File(path);
		if(!file.exists()){//判断是否待删除目录是否存在
			return;
		}

		String[] content = file.list();//取得当前目录下所有文件和文件夹
		if (content != null) {
			for (String name : content) {
				File temp = new File(path, name);
				if (temp.isDirectory()) {//判断是否是目录
					deleteDir(temp.getAbsolutePath());//递归调用，删除目录里的内容
					temp.delete();//删除空目录
				} else {
					if (!temp.delete()) {//直接删除文件
						System.err.println("Failed to delete " + name);
					}
				}
			}
		}
	}

	public static void main(String[] args) {
		File settings = new File(System.getenv("Appdata") + "/settings.json");
		File caches = new File("cache.dat");
		File data = new File("data");
		//清空tmp
		deleteDir(SettingsFrame.tmp.getAbsolutePath());
		if (!settings.exists()){
			String ignorePath = "";
			JSONObject json = new JSONObject();
			json.put("hotkey", "Ctrl + Alt + J");
			json.put("ignorePath", ignorePath);
			json.put("isStartup", false);
			json.put("updateTimeLimit", 300);
			json.put("cacheNumLimit", 1000);
			json.put("searchDepth", 6);
			json.put("priorityFolder", "");
			json.put("dataPath", data.getAbsolutePath());
			try(BufferedWriter buffW = new BufferedWriter(new FileWriter(settings))) {
				buffW.write(json.toJSONString());
			} catch (IOException ignored) {

			}
		}
		File target;
		InputStream fileMonitorDll64 = MainClass.class.getResourceAsStream("/fileMonitor64.dll");
		InputStream fileMonitorDll32 = MainClass.class.getResourceAsStream("/fileMonitor32.dll");

		target = new File("fileMonitor.dll");
		if (!target.exists()) {
			File dll;
			if (name.contains("x64")) {
				copyFile(fileMonitorDll64, target);
				System.out.println("已加载64位dll");
				dll = new File("fileMonitor64.dll");
			}else{
				copyFile(fileMonitorDll32, target);
				System.out.println("已加载32位dll");
				dll = new File("fileMonitor32.dll");
			}
			dll.renameTo(new File("fileMonitor.dll"));
		}
		if (!caches.exists()){
			try {
				caches.createNewFile();
			} catch (IOException e) {
				JOptionPane.showMessageDialog(null, "创建缓存文件失败，程序正在退出");
				mainExit = true;
			}
		}
		if (!SettingsFrame.tmp.exists()){
			SettingsFrame.tmp.mkdir();
		}
		taskBar = new TaskBar();
		taskBar.showTaskBar();


		SettingsFrame.initSettings();

		data = new File(SettingsFrame.dataPath);
		if (data.isDirectory() && data.exists()){
			if (Objects.requireNonNull(data.list()).length == 30){
				System.out.println("检测到data文件，正在读取");
				search.setUsable(false);
				Thread loader = new Thread(()->{
					search.loadAllLists();
					search.setUsable(true);
					System.out.println("读取完成");
				});
				loader.start();
			}else{
				System.out.println("检测到data文件损坏，开始搜索并创建data文件");
				search.setManualUpdate(true);
			}
		}else{
			System.out.println("未检测到data文件，开始搜索并创建data文件");
			search.setManualUpdate(true);
		}

		File[] roots = File.listRoots();
		ExecutorService fixedThreadPool = Executors.newFixedThreadPool(roots.length+4);
		for(File root:roots){
			fixedThreadPool.execute(()-> {
				FileMonitor.INSTANCE.fileWatcher(root.getAbsolutePath(), SettingsFrame.tmp.getAbsolutePath() + "\\fileMonitor.txt", SettingsFrame.tmp.getAbsolutePath() + "\\CLOSE");
			});
		}

		if (!isAdmin()){
			System.out.println("无管理员权限，文件监控功能受限");
			taskBar.showMessage("警告","未获取管理员权限，文件监控功能受限");
		}

		fixedThreadPool.execute(() -> {
			// 时间检测线程
			long count = 0;
			long usingCount = 0;
			while (!mainExit) {
				boolean isUsing = searchBar.getUsing();
				boolean isSleep = searchBar.getSleep();
				count++;
				if (count >= SettingsFrame.updateTimeLimit << 10 && !isUsing && !isSleep && !search.isManualUpdate()) {
					count = 0;
					System.out.println("正在更新本地索引data文件");
					search.saveLists();
				}

				if (!isUsing){
					usingCount++;
					if (usingCount > 900000 && search.isUsable()) {
						System.out.println("检测到长时间未使用，自动释放内存空间，程序休眠");
						searchBar.setSleep(true);
						search.setUsable(false);
						search.saveAndReleaseLists();
					}
				}else{
					usingCount = 0;
					if (!search.isUsable() && !search.isManualUpdate()) {
						System.out.println("检测到开始使用，加载列表");
						search.setUsable(false);
						searchBar.setSleep(false);
						Thread loader = new Thread(()->{
							search.loadAllLists();
							search.setUsable(true);
						});
						loader.start();
					}
				}
				try {
					Thread.sleep(1);
				} catch (InterruptedException ignore) {

				}
			}
		});


		//刷新屏幕线程
		fixedThreadPool.execute(() -> {
			Container panel;
			while (!mainExit) {
				try {
					panel = searchBar.getPanel();
					panel.repaint();
				} catch (Exception ignored) {

				}finally {
					try {
						Thread.sleep(50);
					} catch (InterruptedException ignored) {

					}
				}
			}
		});


		//搜索线程
		fixedThreadPool.execute(() ->{
			while (!mainExit){
				if (search.isManualUpdate()){
					search.setUsable(false);
					System.out.println("已收到更新请求");
					search.updateLists(SettingsFrame.ignorePath, SettingsFrame.searchDepth);
				}
				try {
					Thread.sleep(16);
				} catch (InterruptedException ignored) {

				}
			}
		});

		//检测文件改动线程
		fixedThreadPool.execute(() -> {
			long count = 0;
			while (!mainExit) {
				try (BufferedReader bw = new BufferedReader(new FileReader(fileWatcherTXT))) {
					long loop = 0;
					String line;
					while ((line = bw.readLine()) != null) {
						loop += 1;
						if (loop > count) {
							String[] strings = line.split(" : ");
							switch (strings[0]) {
								case "file add":
									search.FilesToAdd(strings[1]);
									break;
								case "file renamed":
									String[] add = strings[1].split("->");
									search.addToRecycleBin(add[0]);
									search.FilesToAdd(add[1]);
									break;
								case "file removed":
									search.addToRecycleBin(strings[1]);
									break;
							}
							count += 1;
						}
					}
					Thread.sleep(50);
				} catch (IOException | InterruptedException ignored) {

				}
			}
		});



		do {
			// 主循环开始
			if (!search.isIsFocusLost()){
				searchBar.showSearchbar();
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {

			}
			if (mainExit){
				File close = new File(SettingsFrame.tmp.getAbsolutePath() + "\\" + "CLOSE");
				try {
					close.createNewFile();
					if (search.isUsable()) {
						System.out.println("即将退出，保存最新文件列表到data");
						search.mergeFileToList();
						search.saveLists();
					}
				} catch (IOException ignored) {

				}
				fixedThreadPool.shutdown();
				System.exit(0);
			}
		}while(true);
	}
}
