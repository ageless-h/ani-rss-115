package ani.rss.pan115;

import ani.rss.entity.Config;
import ani.rss.pan115.model.Pan115FileInfo;
import ani.rss.pan115.model.Pan115OfflineTask;
import ani.rss.pan115.model.Pan115QrCodeStatus;
import ani.rss.pan115.model.Pan115Session;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * HTTP client for 115 cloud storage API.
 * Handles authentication, file operations, and offline downloads.
 */
@Slf4j
public class Pan115ApiClient {

    private static final String QR_TOKEN_URL = "https://qrcodeapi.115.com/api/1.0/web/1.0/token/";
    private static final String QR_STATUS_URL = "https://qrcodeapi.115.com/get/status/";
    private static final String QR_LOGIN_URL = "https://passportapi.115.com/app/1.0/web/1.0/login/qrcode/";
    private static final String FILE_LIST_URL = "https://webapi.115.com/files";
    private static final String FILE_DOWNLOAD_URL = "https://webapi.115.com/files/download";
    private static final String OFFLINE_ADD_URL = "https://lixian.115.com/web/lixian/?ac=add_task_url";
    private static final String OFFLINE_LIST_URL = "https://webapi.115.com/offline";
    private static final String OFFLINE_CLEAR_URL = "https://webapi.115.com/offline/clear";

    private final Config config;
    private final Pan115CredentialManager credentialManager;
    private final RateLimiter rateLimiter;

    public Pan115ApiClient(Config config, Pan115CredentialManager credentialManager) {
        this.config = config;
        this.credentialManager = credentialManager;
        this.rateLimiter = new RateLimiter(Optional.ofNullable(config.getPan115MinRequestInterval()).orElse(500));
    }

    // ========== Authentication ==========

    public Map<String, String> getQrCodeToken() {
        enforceRateLimit();

        try {
            HttpResponse response = HttpReq.get(QR_TOKEN_URL)
                    .timeout(30000)
                    .execute();

            if (!response.isOk()) {
                log.error("Failed to get QR code token: {}", response.getStatus());
                return null;
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            JSONObject data = json.getJSONObject("data");

            Map<String, String> result = new HashMap<>();
            result.put("uid", data.getStr("uid"));
            result.put("time", data.getStr("time"));
            result.put("sign", data.getStr("sign"));
            result.put("qrcode_url", "https://qrcodeapi.115.com/api/1.0/web/1.0/qrcode?uid=" + data.getStr("uid"));

            return result;
        } catch (Exception e) {
            log.error("Error getting QR code token", e);
            return null;
        }
    }

    public Map<String, Object> pollQrCodeStatus(String uid, String time, String sign) {
        enforceRateLimit();

        Map<String, Object> result = new HashMap<>();
        try {
            String url = QR_STATUS_URL + "?uid=" + uid + "&time=" + time + "&sign=" + sign;
            HttpResponse response = HttpReq.get(url)
                    .timeout(30000)
                    .execute();

            if (!response.isOk()) {
                result.put("status", Pan115QrCodeStatus.EXPIRED);
                return result;
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            JSONObject data = json.getJSONObject("data");
            int statusCode = data.getInt("status", -1);
            Pan115QrCodeStatus status = Pan115QrCodeStatus.fromCode(statusCode);
            
            result.put("status", status);

            // If confirmed, exchange uid for login cookies (status endpoint does not return cookies)
            if (status == Pan115QrCodeStatus.CONFIRMED) {
                Pan115Session session = exchangeQrCodeForSession(uid);
                if (session != null && session.isValid()) {
                    result.put("session", session);
                    credentialManager.setCurrentSession(session.toCookieString(),
                            Instant.now().plus(Duration.ofHours(24)));
                    boolean stored = credentialManager.storeCredentials(session.toCookieString());
                    if (!stored) {
                        log.warn("Failed to persist 115 credentials to encrypted store");
                        result.put("error", "凭证保存失败");
                    }
                    log.info("QR login successful, session established (uid={})", session.getUid());
                } else {
                    log.error("Failed to exchange QR code for session cookies (uid={})", uid);
                    // Surface as a soft error so frontend can show real feedback
                    result.put("error", "登录凭证获取失败");
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Error polling QR code status", e);
            result.put("status", Pan115QrCodeStatus.EXPIRED);
            return result;
        }
    }

    /**
     * 用 uid 与 115 服务器交换最终的登录 cookie。
     * 这一步必须在二维码扫描确认（status=CONFIRMED）后立即调用，
     * 因为 /get/status/ 接口本身并不会返回 cookie。
     */
    private Pan115Session exchangeQrCodeForSession(String uid) {
        try {
            HttpResponse response = HttpReq.post(QR_LOGIN_URL)
                    .form("account", uid)
                    .form("app", "web")
                    .timeout(30000)
                    .execute();

            String body = response.body();
            log.debug("115 qrcode exchange status={} body={}", response.getStatus(), body);

            // 优先尝试从响应 JSON 中的 data.cookie 字段提取
            Pan115Session session = null;
            try {
                JSONObject json = JSONUtil.parseObj(body);
                JSONObject data = json.getJSONObject("data");
                if (data != null) {
                    JSONObject cookieData = data.getJSONObject("cookie");
                    if (cookieData != null) {
                        Pan115Session s = new Pan115Session();
                        s.setUid(cookieData.getStr("UID"));
                        s.setCid(cookieData.getStr("CID"));
                        s.setSeid(cookieData.getStr("SEID"));
                        s.setKid(cookieData.getStr("KID"));
                        if (s.isValid()) {
                            session = s;
                        }
                    }
                }
            } catch (Exception ex) {
                log.debug("Parsing JSON cookie payload failed: {}", ex.getMessage());
            }

            // 兜底：从 Set-Cookie 响应头中提取
            if (session == null) {
                List<String> setCookies = response.headerList("Set-Cookie");
                if (setCookies != null && !setCookies.isEmpty()) {
                    Pan115Session s = new Pan115Session();
                    for (String sc : setCookies) {
                        // Set-Cookie 形如:  UID=xxx; Path=/; Domain=.115.com
                        String first = sc.split(";", 2)[0].trim();
                        String[] kv = first.split("=", 2);
                        if (kv.length != 2) continue;
                        switch (kv[0].trim().toUpperCase()) {
                            case "UID" -> s.setUid(kv[1].trim());
                            case "CID" -> s.setCid(kv[1].trim());
                            case "SEID" -> s.setSeid(kv[1].trim());
                            case "KID" -> s.setKid(kv[1].trim());
                        }
                    }
                    if (s.isValid()) {
                        session = s;
                    }
                }
            }

            return session;
        } catch (Exception e) {
            log.error("Error exchanging QR code for session", e);
            return null;
        }
    }

    // ========== File Operations ==========

    public List<Pan115FileInfo> listFiles(String cid, int offset, int limit) {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null) {
            return Collections.emptyList();
        }

        try {
            HttpResponse response = HttpReq.get(FILE_LIST_URL)
                    .header("Cookie", cookies)
                    .form("cid", StrUtil.blankToDefault(cid, "0"))
                    .form("offset", offset)
                    .form("limit", limit)
                    .form("show_dir", 1)
                    .timeout(30000)
                    .execute();

            if (!response.isOk()) {
                log.error("Failed to list files: {}", response.getStatus());
                return Collections.emptyList();
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            JSONArray files = json.getJSONArray("data");

            List<Pan115FileInfo> result = new ArrayList<>();
            if (files != null) {
                for (int i = 0; i < files.size(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    Pan115FileInfo info = new Pan115FileInfo();
                    info.setFileId(file.getStr("fid"));
                    info.setPickcode(file.getStr("pc"));
                    info.setName(file.getStr("n"));
                    info.setSize(file.getLong("s", 0L));
                    info.setIsDirectory(StrUtil.isNotBlank(file.getStr("cid")) &&
                            (StrUtil.isBlank(file.getStr("fid")) || Objects.equals(file.getStr("fid"), "0")));
                    info.setParentId(cid);
                    result.add(info);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Error listing files", e);
            return Collections.emptyList();
        }
    }

    public String getDownloadUrl(String pickcode) {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null || pickcode == null) {
            return null;
        }

        return executeWithRetry(() -> {
            HttpResponse response = HttpReq.get(FILE_DOWNLOAD_URL)
                    .header("Cookie", cookies)
                    .form("pickcode", pickcode)
                    .timeout(30000)
                    .execute();

            if (!response.isOk()) {
                return null;
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            if (!json.getBool("state", false)) {
                log.error("Failed to get download URL: {}", json.getStr("msg"));
                return null;
            }

            JSONObject data = json.getJSONObject("data");
            if (data == null) {
                return null;
            }

            // Try to get URL directly from data.url structure
            JSONObject urlData = data.getJSONObject("url");
            if (urlData != null) {
                String url = urlData.getStr("url");
                if (url != null && !url.isEmpty()) {
                    return url;
                }
            }

            // Fallback: iterate through data keys
            for (String key : data.keySet()) {
                JSONObject fileData = data.getJSONObject(key);
                if (fileData != null) {
                    String url = fileData.getStr("url");
                    if (url != null && !url.isEmpty()) {
                        return url;
                    }
                }
            }

            return null;
        }, Optional.ofNullable(config.getPan115MaxRetries()).orElse(3));
    }

    public String findOrCreateFolderPath(String folderPath) {
        folderPath = normalizeFolderPath(folderPath);
        if (StrUtil.isBlank(folderPath)) {
            return "0";
        }

        String created = createFolderPath(folderPath, "0");
        if (StrUtil.isNotBlank(created)) {
            return created;
        }

        String currentCid = "0";
        for (String part : folderPath.split("/")) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            String nextCid = findFolderCid(part, currentCid);
            if (StrUtil.isBlank(nextCid)) {
                nextCid = createFolder(part, currentCid);
            }
            if (StrUtil.isBlank(nextCid)) {
                return null;
            }
            currentCid = nextCid;
        }
        return currentCid;
    }

    public String findOrCreateFolder(String folderName) {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null) {
            return null;
        }

        // First try to find existing folder
        try {
            HttpResponse response = HttpReq.get(FILE_LIST_URL)
                    .header("Cookie", cookies)
                    .form("cid", "0")
                    .form("limit", 1000)
                    .form("show_dir", 1)
                    .timeout(30000)
                    .execute();

            if (!response.isOk()) {
                return null;
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            JSONArray files = json.getJSONArray("data");

            if (files != null) {
                for (int i = 0; i < files.size(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    if (isFolder(file) && folderName.equals(file.getStr("n"))) {
                        return file.getStr("cid");
                    }
                }
            }

            // Folder not found, create it
            return createFolder(folderName, "0");
        } catch (Exception e) {
            log.error("Error finding/creating folder", e);
            return null;
        }
    }

    private String createFolderPath(String folderPath, String parentCid) {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null) {
            return null;
        }

        try {
            HttpResponse response = HttpReq.post("https://proapi.115.com/app/chrome/add_path")
                    .header("Cookie", cookies)
                    .header("User-Agent", "Mozilla/5.0 115Browser/99.99.99.99")
                    .form("path", folderPath)
                    .form("parent_id", parentCid)
                    .timeout(30000)
                    .execute();

            String body = response.body();
            if (!response.isOk()) {
                log.warn("Pan115 create folder path failed: HTTP {} {}", response.getStatus(), body);
                return null;
            }

            JSONObject json = JSONUtil.parseObj(body);
            if (!json.getBool("state", false)) {
                log.warn("Pan115 create folder path failed: {}", body);
                return null;
            }

            String cid = extractCid(json);
            if (StrUtil.isNotBlank(cid)) {
                return cid;
            }

            // Some 115 responses only report success. Resolve the final folder id by listing.
            return resolveFolderPath(folderPath, parentCid);
        } catch (Exception e) {
            log.error("Error creating folder path {}", folderPath, e);
            return null;
        }
    }

    private String createFolder(String folderName, String parentCid) {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null) {
            return null;
        }

        try {
            HttpResponse response = HttpReq.post("https://webapi.115.com/files/add")
                    .header("Cookie", cookies)
                    .form("pid", parentCid)
                    .form("name", folderName)
                    .timeout(30000)
                    .execute();

            String body = response.body();
            if (!response.isOk()) {
                log.warn("Pan115 create folder failed: HTTP {} {}", response.getStatus(), body);
                return null;
            }

            JSONObject json = JSONUtil.parseObj(body);
            if (json.getBool("state", false)) {
                String cid = extractCid(json);
                if (StrUtil.isNotBlank(cid)) {
                    return cid;
                }
            }
            
            // Check if error is "folder already exists"
            String errorMsg = StrUtil.blankToDefault(json.getStr("error_msg"), json.getStr("error"));
            if (errorMsg != null && errorMsg.contains("exists")) {
                // Folder was created by another thread/process, find it
                return findFolderCid(folderName, parentCid);
            }

            log.warn("Pan115 create folder failed: {}", body);
            return null;
        } catch (Exception e) {
            log.error("Error creating folder", e);
            return null;
        }
    }
    
    private String findFolderCid(String folderName, String parentCid) {
        try {
            HttpResponse response = HttpReq.get(FILE_LIST_URL)
                    .header("Cookie", getValidCookies())
                    .form("cid", parentCid)
                    .form("limit", 1000)
                    .form("show_dir", 1)
                    .timeout(30000)
                    .execute();

            if (!response.isOk()) {
                return null;
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            JSONArray files = json.getJSONArray("data");

            if (files != null) {
                for (int i = 0; i < files.size(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    if (isFolder(file) && folderName.equals(file.getStr("n"))) {
                        return file.getStr("cid");
                    }
                }
            }

            return null;
        } catch (Exception e) {
            log.error("Error finding folder CID", e);
            return null;
        }
    }

    private String resolveFolderPath(String folderPath, String parentCid) {
        String currentCid = parentCid;
        for (String part : normalizeFolderPath(folderPath).split("/")) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            currentCid = findFolderCid(part, currentCid);
            if (StrUtil.isBlank(currentCid)) {
                return null;
            }
        }
        return currentCid;
    }

    private String normalizeFolderPath(String folderPath) {
        return StrUtil.nullToEmpty(folderPath)
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .replaceAll("^/|/$", "");
    }

    private boolean isFolder(JSONObject file) {
        return StrUtil.isNotBlank(file.getStr("cid")) &&
                (StrUtil.isBlank(file.getStr("fid")) || Objects.equals(file.getStr("fid"), "0"));
    }

    private String extractCid(JSONObject json) {
        JSONObject data = json.getJSONObject("data");
        if (data != null) {
            String cid = data.getStr("cid");
            if (StrUtil.isNotBlank(cid)) {
                return cid;
            }
            cid = data.getStr("file_id");
            if (StrUtil.isNotBlank(cid)) {
                return cid;
            }
            cid = data.getStr("id");
            if (StrUtil.isNotBlank(cid)) {
                return cid;
            }
        }

        JSONArray dataArray = json.getJSONArray("data");
        if (dataArray != null && !dataArray.isEmpty()) {
            JSONObject first = dataArray.getJSONObject(dataArray.size() - 1);
            if (first != null) {
                String cid = first.getStr("cid");
                if (StrUtil.isNotBlank(cid)) {
                    return cid;
                }
                cid = first.getStr("file_id");
                if (StrUtil.isNotBlank(cid)) {
                    return cid;
                }
                cid = first.getStr("id");
                if (StrUtil.isNotBlank(cid)) {
                    return cid;
                }
            }
        }

        return null;
    }

    // ========== Offline Download ==========

    public String addOfflineDownload(String url, String savePath) {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null) {
            return null;
        }

        try {
            HttpRequest request = HttpReq.post(OFFLINE_ADD_URL)
                    .header("Cookie", cookies)
                    .header("Referer", "https://115.com/")
                    .header("Origin", "https://115.com")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("User-Agent", "Mozilla/5.0 115Browser/99.99.99.99")
                    .form("url", url)
                    .timeout(30000);

            if (StrUtil.isNotBlank(savePath)) {
                request.form("savepath", savePath);
            }

            HttpResponse response = request.execute();
            String body = response.body();

            if (!response.isOk()) {
                log.error("Failed to add offline download: HTTP {} {}", response.getStatus(), body);
                return null;
            }

            JSONObject json = JSONUtil.parseObj(body);
            if (!json.getBool("state", false)) {
                String error = StrUtil.blankToDefault(json.getStr("error_msg"), json.getStr("error"));
                log.error("Failed to add offline download: {}", StrUtil.blankToDefault(error, body));
                return null;
            }

            JSONObject data = json.getJSONObject("data");
            if (data != null) {
                String infoHash = data.getStr("info_hash");
                if (StrUtil.isNotBlank(infoHash)) {
                    return infoHash;
                }
            }

            String infoHash = json.getStr("info_hash");
            if (StrUtil.isNotBlank(infoHash)) {
                return infoHash;
            }

            JSONObject result = json.getJSONObject("result");
            if (result != null) {
                infoHash = result.getStr("info_hash");
                if (StrUtil.isNotBlank(infoHash)) {
                    return infoHash;
                }
            }
            JSONArray resultList = json.getJSONArray("result");
            if (resultList != null && !resultList.isEmpty()) {
                JSONObject first = resultList.getJSONObject(0);
                infoHash = first.getStr("info_hash");
                if (StrUtil.isNotBlank(infoHash)) {
                    return infoHash;
                }
            }

            log.info("Pan115 offline add response: {}", body);
            return url;
        } catch (Exception e) {
            log.error("Error adding offline download", e);
            return null;
        }
    }

    public List<Pan115OfflineTask> listOfflineTasks() {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null) {
            return Collections.emptyList();
        }

        try {
            HttpResponse response = HttpReq.get(OFFLINE_LIST_URL)
                    .header("Cookie", cookies)
                    .form("page", 1)
                    .form("limit", 100)
                    .timeout(30000)
                    .execute();

            if (!response.isOk()) {
                return Collections.emptyList();
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            JSONArray tasks = json.getJSONArray("tasks");

            List<Pan115OfflineTask> result = new ArrayList<>();
            if (tasks != null) {
                for (int i = 0; i < tasks.size(); i++) {
                    JSONObject task = tasks.getJSONObject(i);
                    Pan115OfflineTask offlineTask = new Pan115OfflineTask();
                    offlineTask.setTaskId(task.getStr("info_hash"));
                    offlineTask.setName(task.getStr("name"));
                    offlineTask.setUrl(task.getStr("url"));
                    offlineTask.setStatus(task.getInt("status", 0));
                    offlineTask.setSize(task.getLong("size", 0L));
                    offlineTask.setProgress(task.getDouble("percent", 0.0));
                    result.add(offlineTask);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Error listing offline tasks", e);
            return Collections.emptyList();
        }
    }

    public boolean clearCompletedOfflineTasks() {
        enforceRateLimit();

        String cookies = getValidCookies();
        if (cookies == null) {
            return false;
        }

        try {
            HttpResponse response = HttpReq.post(OFFLINE_CLEAR_URL)
                    .header("Cookie", cookies)
                    .form("flag", 1)
                    .timeout(30000)
                    .execute();

            return response.isOk();
        } catch (Exception e) {
            log.error("Error clearing offline tasks", e);
            return false;
        }
    }

    // ========== Session Management ==========

    public boolean isSessionValid() {
        String cookies = credentialManager.getCurrentSession();
        if (cookies == null) {
            cookies = credentialManager.retrieveCredentials();
        }

        if (cookies == null) {
            return false;
        }

        try {
            HttpResponse response = HttpReq.get(FILE_LIST_URL)
                    .header("Cookie", cookies)
                    .form("cid", "0")
                    .form("limit", 1)
                    .timeout(10000)
                    .execute();

            return response.isOk();
        } catch (Exception e) {
            return false;
        }
    }

    // ========== Private Methods ==========

    private String getValidCookies() {
        // First check memory session
        String cookies = credentialManager.getCurrentSession();
        if (cookies != null) {
            // Verify session is still valid
            if (isSessionValid()) {
                return cookies;
            } else {
                log.warn("Memory session expired, clearing");
                credentialManager.clearSession();
            }
        }

        // Fall back to stored credentials
        cookies = credentialManager.retrieveCredentials();
        if (cookies != null) {
            // Verify stored credentials are still valid
            if (isSessionValid()) {
                // Refresh memory session
                credentialManager.setCurrentSession(cookies, Instant.now().plus(Duration.ofHours(24)));
                return cookies;
            } else {
                log.error("Stored credentials expired, user needs to re-login");
                credentialManager.wipeAll();
            }
        }

        return null;
    }

    private Pan115Session parseCookies(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }

        Pan115Session session = new Pan115Session();
        String[] cookies = cookieHeader.split(";");

        for (String cookie : cookies) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length != 2) continue;

            String name = parts[0].trim();
            String value = parts[1].trim();

            switch (name.toUpperCase()) {
                case "UID" -> session.setUid(value);
                case "CID" -> session.setCid(value);
                case "SEID" -> session.setSeid(value);
                case "KID" -> session.setKid(value);
            }
        }

        return session.isValid() ? session : null;
    }

    private void enforceRateLimit() {
        rateLimiter.acquire();
    }

    private <T> T executeWithRetry(Supplier<T> action, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                T result = action.get();
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", i + 1, e.getMessage());
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(1000L * (i + 1));
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * Thread-safe rate limiter using synchronized block.
     */
    private static class RateLimiter {
        private final long minIntervalMs;
        private long lastRequestTime = 0;

        RateLimiter(int minIntervalMs) {
            this.minIntervalMs = minIntervalMs;
        }

        synchronized void acquire() {
            try {
                long now = System.currentTimeMillis();
                long elapsed = now - lastRequestTime;

                if (elapsed < minIntervalMs) {
                    Thread.sleep(minIntervalMs - elapsed);
                }

                lastRequestTime = System.currentTimeMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
