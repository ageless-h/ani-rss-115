package ani.rss.pan115.model;

import lombok.Data;

import java.time.Instant;

/**
 * 115 offline download task information.
 */
@Data
public class Pan115OfflineTask {
    private String taskId;
    private String name;
    private String url;
    private Integer status;
    private Long size;
    private Long downloadedSize;
    private Double progress;
    private Instant createdTime;
    private String errorMessage;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_COMPLETE = 2;
    public static final int STATUS_FAILED = -1;

    /**
     * Get status description.
     *
     * @return Status description in Chinese
     */
    public String getStatusText() {
        return switch (status) {
            case STATUS_PENDING -> "等待中";
            case STATUS_DOWNLOADING -> "下载中";
            case STATUS_COMPLETE -> "完成";
            case STATUS_FAILED -> "失败";
            default -> "未知";
        };
    }

    /**
     * Check if task is complete.
     *
     * @return true if complete
     */
    public boolean isComplete() {
        return status != null && status == STATUS_COMPLETE;
    }

    /**
     * Check if task failed.
     *
     * @return true if failed
     */
    public boolean isFailed() {
        return status != null && status == STATUS_FAILED;
    }

    /**
     * Get download progress percentage.
     *
     * @return Progress 0-100
     */
    public double getProgressPercent() {
        if (progress != null) {
            return progress * 100;
        }
        if (size != null && size > 0 && downloadedSize != null) {
            return (downloadedSize * 100.0) / size;
        }
        return 0;
    }
}
