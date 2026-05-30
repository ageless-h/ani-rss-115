package ani.rss.pan115.model;

import lombok.Data;

import java.time.Instant;

/**
 * 115 cloud file information.
 */
@Data
public class Pan115FileInfo {
    private String fileId;
    private String pickcode;
    private String name;
    private Long size;
    private Boolean isDirectory;
    private String parentId;
    private String parentPath;
    private Instant modifiedTime;
    private String sha1;

    /**
     * Get file extension.
     *
     * @return File extension or empty string
     */
    public String getExtension() {
        if (name == null || name.lastIndexOf('.') == -1) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * Check if file is a video.
     *
     * @return true if video file
     */
    public boolean isVideo() {
        String ext = getExtension();
        return ext.matches("^(mp4|mkv|avi|mov|wmv|flv|webm|m4v|mpg|mpeg|ts|m2ts)$");
    }
}
