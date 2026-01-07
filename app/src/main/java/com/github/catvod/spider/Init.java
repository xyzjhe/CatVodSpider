package com.github.catvod.spider;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Init {

    private final ExecutorService executor;
    private final Handler handler;
    private Application app;

    private volatile Socket healthSocket;
    private volatile boolean isRunning = false;
    private volatile Thread healthCheckThread;
    private static final int HEALTH_PORT = 5575;
    private static final String HEALTH_PATH = "/health";
    private static final int HEALTH_TIMEOUT = 3000; // 3秒超时
    private static final long HEALTH_INTERVAL = 1000; // 1秒间隔


    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }

    public static Init get() {
        return Loader.INSTANCE;
    }

    public Init() {
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(5);
    }

    public static Application context() {
        return get().app;
    }

    public static void init(Context context) {
        get().app = ((Application) context);
        Proxy.init();

        initGoProxy(context);
        
        DanmakuSpider.doInitWork(context,"");

        // 启动Hook监控
        DanmakuScanner.startHookMonitor();
        DanmakuSpider.log("Leo弹幕监控已启动");
    }

    private static void initGoProxy(Context context) {
        SpiderDebug.log("自定義爬蟲代碼載入成功！");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            get().handler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, "安卓版本过低，无法启动goProxy", Toast.LENGTH_SHORT).show();
                }
            });

            return;
        }

        List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
        execute(new Runnable() {
            @Override
            public void run() {
                try {
                    //x86_64, arm64-v8a, x86, armeabi-v7a, armeabi
                    String goProxy = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
//                String goProxy = abs.contains("arm64-v8a") ? "Omnibox-arm64" : "Omnibox-arm";
                    File file = new File(context.getCacheDir(), goProxy);

                    Process exec = Runtime.getRuntime().exec("/system/bin/sh");
                    try (DataOutputStream dos = new DataOutputStream(exec.getOutputStream())) {
                        if (!file.exists()) {
                            if (!file.createNewFile()) {
                                throw new Exception("创建文件失败 " + file);
                            }

                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                try (InputStream is = Objects.requireNonNull(get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxy)) {
                                    int read;
                                    byte[] buffer = new byte[8192];
                                    while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                                }
                            }

                            if (!file.setExecutable(true)) {
                                throw new Exception(goProxy + " setExecutable is false");
                            }

                            dos.writeBytes("chmod 777 " + file.getAbsolutePath() + "\n");
                            dos.flush();
                        }

                        SpiderDebug.log("启动 " + file);
                        dos.writeBytes("kill $(ps -ef | grep '" + goProxy + "' | grep -v grep | awk '{print $2}')\n");
                        dos.flush();

                        dos.writeBytes("nohup " + file.getAbsolutePath() + "\n");
                        dos.flush();

                        dos.writeBytes("exit\n");
                        dos.flush();

                        get().handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(context, "加载：" + goProxy + "成功", Toast.LENGTH_SHORT).show();
                            }
                        });

                        // 启动go代理监控检查
                        get().startHealthCheck(context);
                    }

                    try (InputStream is = exec.getInputStream()) {
                        log(is, "input");
                    }
                    try (InputStream is = exec.getErrorStream()) {
                        log(is, "err");
                    }
                    SpiderDebug.log("exe ret " + exec.waitFor());
                } catch (Exception ex) {
                    SpiderDebug.log("启动 goProxy异常：" + ex.getMessage());
                    get().handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, abs + "启动 goProxy异常：" + ex.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    /**
     * 启动健康检查线程
     * @param context
     */
    private void startHealthCheck(Context context) {
        // 避免重复启动
        if (isRunning) {
            return;
        }
        
        isRunning = true;

        // 创建长连接心跳检查线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        // 使用更长的间隔来避免过于频繁
                        Thread.sleep(HEALTH_INTERVAL); // 每1秒执行一次健康检查
                    } catch (InterruptedException ie) {
                        SpiderDebug.log("Health check thread interrupted");
                        break; // 线程被中断则退出循环
                    }

                    boolean isHealthy = false;
                    try {
                        JsonObject json = new Gson().fromJson(OkHttp.string("http://127.0.0.1:5575/health"), JsonObject.class);
                        if (json.get("status").getAsString().equals("healthy")) {
                            SpiderDebug.log("Health check passed");
                            isHealthy = true;
                        } else {
                            SpiderDebug.log("Health check status not healthy");
                        }
                    } catch (Exception e) {
                        SpiderDebug.log("Error during health check: " + e.getMessage());
                    }

                    if (!isHealthy) {
                        closeHealthSocket();
                        // 连接失败时重启Go代理
                        try {
                            initGoProxy(context);
                            SpiderDebug.log("Health check failed, restarting goProxy");
                            // 等待一段时间让新服务启动
                            Thread.sleep(3000);
                        } catch (Exception restartEx) {
                            SpiderDebug.log("Failed to restart goProxy: " + restartEx.getMessage());
                        }
                    }
                }

                // 清理资源
                closeHealthSocket();
                SpiderDebug.log("Health check thread stopped");
            }
        }).start();

        SpiderDebug.log("Health check thread started");
    }

    // 添加关闭健康检查连接的方法
    private void closeHealthSocket() {
        try {
            if (healthSocket != null && !healthSocket.isClosed()) {
                healthSocket.close();
            }
        } catch (IOException e) {
            SpiderDebug.log("Error closing health socket: " + e.getMessage());
        } finally {
            healthSocket = null;
        }
    }

    // 添加停止心跳检查的方法
    public static void stopHealthCheck() {
        Init instance = get();
        instance.isRunning = false;
        if (instance.healthCheckThread != null && instance.healthCheckThread.isAlive()) {
            instance.healthCheckThread.interrupt();
        }
        instance.closeHealthSocket();
        SpiderDebug.log("Health check stopped");
    }


    public static void log(InputStream stream, String type) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }

        try (InputStreamReader isr = new InputStreamReader(stream, "UTF-8")) {
            try (BufferedReader br = new BufferedReader(isr)) {
                String readLine;
                while ((readLine = br.readLine()) != null) {
                    SpiderDebug.log(type + ": " + readLine);
                }
            }
        }
    }

    public static void execute(Runnable runnable) {
        get().executor.execute(runnable);
    }

    public static void run(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void run(Runnable runnable, int delay) {
        get().handler.postDelayed(runnable, delay);
    }

    public static void checkPermission() {
        try {
            Activity activity = Init.getActivity();
            if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
                return;
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9999);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Activity getActivity() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
        activitiesField.setAccessible(true);
        Map<?, ?> activities = (Map<?, ?>) activitiesField.get(activityThread);
        for (Object activityRecord : activities.values()) {
            Class<?> activityRecordClass = activityRecord.getClass();
            Field pausedField = activityRecordClass.getDeclaredField("paused");
            pausedField.setAccessible(true);
            if (!pausedField.getBoolean(activityRecord)) {
                Field activityField = activityRecordClass.getDeclaredField("activity");
                activityField.setAccessible(true);
                Activity activity = (Activity) activityField.get(activityRecord);
                SpiderDebug.log(activity.getComponentName().getClassName());
                return activity;
            }
        }
        return null;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, int delay) {
        get().handler.postDelayed(runnable, delay);
    }
}
