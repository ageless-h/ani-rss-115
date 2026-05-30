package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.TorrentsTags;
import ani.rss.pan115.Pan115ApiClient;
import ani.rss.pan115.Pan115CredentialManager;
import ani.rss.pan115.model.Pan115FileInfo;
import ani.rss.pan115.model.Pan115OfflineTask;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 115 cloud storage downloader implementation.
 * Supports cloud-only storage or cloud-to-local download modes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Pan115 implements BaseDownload {

    private Config config;
    private Pan115CredentialManager credentialManager;
    private Pan115ApiClient apiClient;

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
        this.credentialManager = new Pan115CredentialManager(config);
        this.apiClient = new Pan115ApiClient(config, credentialManager);

        boolean initialized = credentialManager.initialize();
        if (!initialized) {
            log.warn("Pan115 凭证管理器初始化失败");
            return false;
        }

        // Check if we have valid credentials
        boolean hasCredentials = credentialManager.hasCredentials();
        if (!hasCredentials) {
            log.warn("Pan115 未配置登录凭证，请先在设置中扫码登录");
            return false;
        }

        if (!Boolean.TRUE.equals(config.getPan115Enabled())) {
            config.setPan115Enabled(true);
            log.info("Pan115 已检测到加密凭证，自动恢复启用状态");
        }

        // 非测试模式下不再发起 isSessionValid 网络校验，避免单次网络抖动导致
        // 整个 RSS 任务被 TorrentUtil.login() 拦截（之前会出现"添加订阅后毫无下载日志"的现象）。
        // 真正的失败会在后续的离线下载请求中以明确的错误日志暴露出来。
        if (!test) {
            // 使用 debug 级别，避免被 rename-task/rss-task 等周期任务反复刷屏
            log.debug("Pan115 登录就绪 (已存在加密凭证)");
            return true;
        }

        boolean valid = apiClient.isSessionValid();
        log.info("Pan115 login test: {}", valid ? "success" : "failed");
        return valid;
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile, Boolean ova) {
        if (apiClient == null) {
            log.error("Pan115 not initialized");
            return false;
        }

        String name = item.getReName();
        log.info("Pan115 adding offline download: {}", name);

        // Extract magnet/URL from torrent file
        String url = extractUrl(torrentFile);
        if (url == null) {
            log.error("Failed to extract URL from torrent file: {}", torrentFile.getName());
            return false;
        }

        String cloudSavePath = toCloudFolderPath(savePath);

        // Add to offline download queue
        String taskId = apiClient.addOfflineDownload(url, cloudSavePath);
        if (taskId == null) {
            log.error("Pan115 failed to add offline download: {}", name);
            return false;
        }

        log.info("Pan115 offline task added: {} (taskId: {})", name, taskId);

        if (!shouldDownloadToLocal(ani)) {
            log.info("Pan115 cloud-only mode: {} task submitted to 115 cloud", name);
            return true;
        }

        // Wait for offline completion with timeout
        Pan115OfflineTask task = waitForOfflineCompletion(taskId);
        if (task == null) {
            log.error("Pan115 offline download timeout: {}", name);
            return false;
        }

        if (task.isFailed()) {
            log.error("Pan115 offline download failed: {} - {}", name, task.getErrorMessage());
            return false;
        }

        log.info("Pan115 offline download complete: {}", name);

        log.info("Pan115 downloading to local: {}", name);
        return downloadToLocal(task, savePath, name, ova);
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        if (apiClient == null) {
            return Collections.emptyList();
        }

        List<Pan115OfflineTask> tasks = apiClient.listOfflineTasks();
        List<TorrentsInfo> result = new ArrayList<>();

        for (Pan115OfflineTask task : tasks) {
            TorrentsInfo info = new TorrentsInfo();
            info.setId(task.getTaskId());
            info.setName(task.getName());
            info.setHash(task.getTaskId());
            info.setSize(task.getSize());
            info.setProgress(task.getProgressPercent());

            // Map status
            if (task.isComplete()) {
                info.setState(TorrentsInfo.State.pausedUP);
            } else if (task.isFailed()) {
                info.setState(TorrentsInfo.State.error);
            } else {
                info.setState(TorrentsInfo.State.downloading);
            }

            info.setTags(List.of(TorrentsTags.ANI_RSS.getValue()));
            result.add(info);
        }

        return result;
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        // 115 doesn't support deleting specific offline tasks easily
        // We can only clear all completed tasks
        if (apiClient != null) {
            apiClient.clearCompletedOfflineTasks();
        }
        return true;
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        // 115 cloud files can't be renamed during download
        // Files are renamed when downloaded to local (if local mode)
        return true;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        // 115 doesn't support tags
        return true;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        // Not applicable for 115
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        // Not applicable - offline download location is set during add
    }

    // ========== Private Methods ==========

    private String extractUrl(File torrentFile) {
        if (torrentFile == null || !torrentFile.exists()) {
            return null;
        }
        // 复用项目通用工具，支持 .txt(magnet/ed2k) 与 .torrent(根据 hash 或解析二进制)
        try {
            String magnet = ani.rss.util.other.TorrentUtil.getMagnet(torrentFile);
            if (StrUtil.isBlank(magnet)) {
                return null;
            }
            return magnet.trim();
        } catch (Exception e) {
            log.error("解析种子链接失败 {}", torrentFile.getName(), e);
            return null;
        }
    }

    private String toCloudFolderPath(String localPath) {
        if (StrUtil.isBlank(localPath)) {
            return "ani-rss-downloads";
        }

        String normalized = localPath.replace('\\', '/');
        List<String> parts = Arrays.stream(normalized.split("/+"))
                .filter(StrUtil::isNotBlank)
                .toList();
        if (parts.isEmpty()) {
            return "ani-rss-downloads";
        }

        List<String> roots = List.of("Media", "番剧", "剧场版", "已完结番剧");
        for (int i = 0; i < parts.size(); i++) {
            if (roots.contains(parts.get(i))) {
                return String.join("/", parts.subList(i, parts.size()));
            }
        }

        int from = Math.max(0, parts.size() - 2);
        return String.join("/", parts.subList(from, parts.size()));
    }

    private Pan115OfflineTask waitForOfflineCompletion(String taskId) {
        int timeoutMinutes = Optional.ofNullable(config.getPan115OfflineTimeout()).orElse(60);
        Instant startTime = Instant.now();
        Instant timeout = startTime.plus(Duration.ofMinutes(timeoutMinutes));

        while (Instant.now().isBefore(timeout)) {
            ThreadUtil.sleep(10000); // Check every 10 seconds

            List<Pan115OfflineTask> tasks = apiClient.listOfflineTasks();
            for (Pan115OfflineTask task : tasks) {
                if (taskId.equals(task.getTaskId())) {
                    if (task.isComplete()) {
                        return task;
                    }
                    if (task.isFailed()) {
                        return task;
                    }
                    // Still downloading, continue waiting
                    log.debug("Pan115 task progress: {}% - {}",
                            String.format("%.1f", task.getProgressPercent()), task.getName());
                }
            }
        }

        log.warn("Pan115 offline download timeout after {} minutes", timeoutMinutes);
        return null;
    }

    private boolean shouldDownloadToLocal(Ani ani) {
        String mode = StrUtil.blankToDefault(config.getPan115DownloadMode(), "hybrid");

        if ("cloud_only".equals(mode)) {
            return false;
        }
        if ("local_only".equals(mode)) {
            return true;
        }

        // hybrid mode - check default setting
        return Boolean.TRUE.equals(config.getPan115DefaultToLocal());
    }

    private boolean downloadToLocal(Pan115OfflineTask task, String savePath, String name, Boolean ova) {
        try {
            // Find the file on 115 cloud
            List<Pan115FileInfo> files = apiClient.listFiles(null, 0, 100);
            Pan115FileInfo targetFile = null;

            for (Pan115FileInfo file : files) {
                // Exact match preferred, or file name starts with task name
                if (file.getName().equals(task.getName()) ||
                        file.getName().startsWith(task.getName() + ".") ||
                        file.getName().startsWith(task.getName() + "_")) {
                    targetFile = file;
                    break;
                }
            }

            if (targetFile == null) {
                log.error("Pan115 file not found on cloud: {}", task.getName());
                return false;
            }

            // Get download URL
            String downloadUrl = apiClient.getDownloadUrl(targetFile.getPickcode());
            if (downloadUrl == null) {
                log.error("Pan115 failed to get download URL: {}", task.getName());
                return false;
            }

            // Create local file path
            String fileName = getFileReName(targetFile.getName(), name);
            Path localFile = Path.of(savePath, fileName);
            Files.createDirectories(localFile.getParent());

            // Download with resume support
            long existingSize = Files.exists(localFile) ? Files.size(localFile) : 0;
            long totalSize = targetFile.getSize();

            if (existingSize >= totalSize) {
                log.info("Pan115 file already downloaded: {}", fileName);
                return true;
            }

            log.info("Pan115 downloading: {} ({} bytes)", fileName, totalSize - existingSize);

            // Download file
            HttpResponse response = HttpReq.get(downloadUrl)
                    .header("Range", "bytes=" + existingSize + "-")
                    .timeout(300000) // 5 minutes
                    .execute();

            if (!response.isOk()) {
                log.error("Pan115 download failed: HTTP {}", response.getStatus());
                return false;
            }

            // Write to file
            byte[] data = response.bodyBytes();
            if (existingSize > 0) {
                Files.write(localFile, data, StandardOpenOption.APPEND);
            } else {
                Files.write(localFile, data);
            }

            log.info("Pan115 download complete: {}", fileName);
            return true;

        } catch (Exception e) {
            log.error("Pan115 local download failed: {}", name, e);
            return false;
        }
    }
}
