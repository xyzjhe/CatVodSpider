package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.catvod.spider.Init.get;

public class GoProxySpider extends Spider {
    private static final ExecutorService executor = Executors.newFixedThreadPool(5);
    private static volatile boolean isRunning = false;
    private static Timer healthCheckTimer;
    private static volatile boolean isFirstHealthCheck; // 新增：用于标记是否是首次健康检查

    private static String goProxy = "";
    private static final long HEALTH_INTERVAL = 1000; // 1秒间隔

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        initGoProxy(context);
    }

    /**
     * 启动健康检查线程
     *
     * @param context
     */
    private static void startHealthCheck(Context context) {
        isFirstHealthCheck = true; // 重置首次检查标记

        if (healthCheckTimer != null) {
            SpiderDebug.log("Health check timer already running");
            return;
        }

        healthCheckTimer = new Timer("GoProxySpider", true);
        healthCheckTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                boolean isHealthy = false;
                try {
                    JsonObject json = new Gson().fromJson(OkHttp.string("http://127.0.0.1:5575/health"), JsonObject.class);
                    if (json != null && json.has("status") && json.get("status").getAsString().equals("healthy")) {
                        SpiderDebug.log("Health check passed");
                        isHealthy = true;
                    } else {
                        SpiderDebug.log("Health check status not healthy");
                    }
                } catch (Exception e) {
                    SpiderDebug.log("Error during health check: " + e.getMessage());
                }

                if (isHealthy) {
                    // **优化点**: 如果是首次检查成功，并且有回调任务，则执行它
                    if (isFirstHealthCheck) {
                        get().handler.post(() -> Toast.makeText(context,"加载：" + goProxy + "成功", Toast.LENGTH_SHORT).show());
                        isFirstHealthCheck = false; // 不再是首次
                    }
                } else {
                    try {
                        initGoProxy(context);
                        SpiderDebug.log("Health check failed, restarting goProxy");
                        Thread.sleep(3000);
                    } catch (Exception restartEx) {
                        SpiderDebug.log("Failed to restart goProxy: " + restartEx.getMessage());
                    }
                }

                try {
                    Thread.sleep(HEALTH_INTERVAL);
                } catch (InterruptedException ie) {
                    SpiderDebug.log("Health check thread interrupted");
                }
            }
        }, 0, 1000);

        SpiderDebug.log("Health check thread started");
    }

    public static void initGoProxy(Context context) {
        SpiderDebug.log("自定義爬蟲代碼載入成功！");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            get().handler.post(() -> Toast.makeText(context, "安卓版本过低，无法启动goProxy", Toast.LENGTH_SHORT).show());
            return;
        }

        List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
        execute(() -> {
            try {
                goProxy = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
                File file = new File(context.getCacheDir(), goProxy);

                Process exec = Runtime.getRuntime().exec("/system/bin/sh");
                try (DataOutputStream dos = new DataOutputStream(exec.getOutputStream())) {
                    if (!file.exists()) {
                        if (!file.createNewFile()) throw new Exception("创建文件失败 " + file);

                        // 获取资源输入流
                        InputStream is = Objects.requireNonNull(get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxy);
                        if (is == null) {
                            throw new Exception("资源文件不存在: assets/" + goProxy);
                        }

                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                        }
                        if (!file.setExecutable(true)) throw new Exception(goProxy + " setExecutable is false");
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

                    // 启动心跳检查
                    startHealthCheck(context);
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
                safeRunOnUiThread(() -> Toast.makeText(context, abs + "启动 goProxy异常：" + ex.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    public static void log(InputStream stream, String type) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            String readLine;
            while ((readLine = br.readLine()) != null) {
                SpiderDebug.log(type + ": " + readLine);
            }
        }
    }

    public static void execute(Runnable runnable) {
        executor.execute(runnable);
    }


    // 获取Top Activity
    public static Activity getTopActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<Object, Object> activities = (Map<Object, Object>) activitiesField.get(activityThread);

            for (Object activityRecord : activities.values()) {
                Class<?> activityRecordClass = activityRecord.getClass();
                java.lang.reflect.Field pausedField = activityRecordClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                if (!pausedField.getBoolean(activityRecord)) {
                    java.lang.reflect.Field activityField = activityRecordClass.getDeclaredField("activity");
                    activityField.setAccessible(true);
                    return (Activity) activityField.get(activityRecord);
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("获取TopActivity失败: " + e.getMessage());
        }
        return null;
    }

    // 安全运行UI线程
    public static void safeRunOnUiThread(Runnable runnable) {
        Activity activity = getTopActivity();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            activity.runOnUiThread(runnable);
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        SpiderDebug.log("homeContent " + filter);
        return "";
    }

    @Override
    public String homeVideoContent() throws Exception {
        SpiderDebug.log("homeVideoContent");
        return "";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("categoryContent " + tid + " " + pg + " " + filter);
        return "";
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        SpiderDebug.log("detailContent " + ids.get(0));
        return "";
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        SpiderDebug.log("searchContent " + key + " " + quick);
        return "";
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        SpiderDebug.log("searchContent " + key + " " + quick + " " + pg);
        return "";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        SpiderDebug.log("playerContent " + flag + " " + id);

        return "";
    }

    @Override
    public String liveContent(String url) throws Exception {
        SpiderDebug.log("liveContent " + url);
        return "";
    }
}
