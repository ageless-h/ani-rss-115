package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Config;
import ani.rss.entity.web.Result;
import ani.rss.pan115.Pan115ApiClient;
import ani.rss.pan115.Pan115CredentialManager;
import ani.rss.pan115.model.Pan115QrCodeStatus;
import ani.rss.pan115.model.Pan115Session;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 115 网盘相关接口（扫码登录、状态查询、退出登录）
 */
@Slf4j
@RestController
@RequestMapping("/pan115")
public class Pan115Controller extends BaseController {

    @Auth
    @Operation(summary = "获取 115 扫码登录二维码")
    @PostMapping("/qrcode")
    public Result<Map<String, Object>> getQrCode() {
        Config config = ConfigUtil.CONFIG;
        Pan115CredentialManager credentialManager = new Pan115CredentialManager(config);
        Pan115ApiClient apiClient = new Pan115ApiClient(config, credentialManager);

        Map<String, String> qrData = apiClient.getQrCodeToken();
        if (qrData == null) {
            return Result.error("获取二维码失败");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uid", qrData.get("uid"));
        result.put("time", qrData.get("time"));
        result.put("sign", qrData.get("sign"));
        result.put("qrcodeUrl", qrData.get("qrcode_url"));
        result.put("status", "waiting");
        return Result.success(result);
    }

    @Auth
    @Operation(summary = "轮询 115 扫码登录状态")
    @PostMapping("/qrcode/status")
    public Result<Map<String, Object>> getQrCodeStatus(
            @RequestParam String uid,
            @RequestParam String time,
            @RequestParam String sign) {
        if (StrUtil.hasBlank(uid, time, sign)) {
            return Result.error("缺少必要参数");
        }

        Config config = ConfigUtil.CONFIG;
        Pan115CredentialManager credentialManager = new Pan115CredentialManager(config);
        Pan115ApiClient apiClient = new Pan115ApiClient(config, credentialManager);

        Map<String, Object> pollResult = apiClient.pollQrCodeStatus(uid, time, sign);
        Pan115QrCodeStatus status = (Pan115QrCodeStatus) pollResult.get("status");

        Map<String, Object> result = new HashMap<>();
        result.put("status", status.name().toLowerCase());
        result.put("description", status.getDescription());

        if (status == Pan115QrCodeStatus.CONFIRMED) {
            Pan115Session session = (Pan115Session) pollResult.get("session");
            if (session != null && session.isValid()) {
                // 登录成功后自动启用 Pan115 并持久化
                config.setPan115Enabled(true);
                ConfigUtil.sync();
                result.put("success", true);
                result.put("message", "登录成功");
            } else {
                result.put("success", false);
                result.put("error", "解析登录凭证失败");
            }
        }

        return Result.success(result);
    }

    @Auth
    @Operation(summary = "查询 115 登录与启用状态")
    @PostMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        Config config = ConfigUtil.CONFIG;
        Pan115CredentialManager credentialManager = new Pan115CredentialManager(config);
        Pan115ApiClient apiClient = new Pan115ApiClient(config, credentialManager);

        boolean initialized = credentialManager.initialize();
        boolean hasCredentials = credentialManager.hasCredentials();
        boolean sessionValid = hasCredentials && apiClient.isSessionValid();

        Map<String, Object> result = new HashMap<>();
        result.put("enabled", config.getPan115Enabled());
        result.put("initialized", initialized);
        result.put("hasCredentials", hasCredentials);
        result.put("sessionValid", sessionValid);
        result.put("downloadMode", config.getPan115DownloadMode());
        return Result.success(result);
    }

    @Auth
    @Operation(summary = "退出 115 登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        Config config = ConfigUtil.CONFIG;
        Pan115CredentialManager credentialManager = new Pan115CredentialManager(config);
        credentialManager.wipeAll();
        config.setPan115Enabled(false);
        ConfigUtil.sync();
        return Result.success("退出登录成功");
    }
}
