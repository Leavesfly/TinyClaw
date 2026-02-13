package io.leavesfly.tinyclaw.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息通道配置类
 * 支持多个消息平台：Telegram、Discord、WhatsApp、飞书、钉钉、QQ、MaixCam
 */
public class ChannelsConfig {
    
    private TelegramConfig telegram;
    private DiscordConfig discord;
    private WhatsAppConfig whatsapp;
    private FeishuConfig feishu;
    private DingTalkConfig dingtalk;
    private QQConfig qq;
    private MaixCamConfig maixcam;
    
    public ChannelsConfig() {
        this.telegram = new TelegramConfig();
        this.discord = new DiscordConfig();
        this.whatsapp = new WhatsAppConfig();
        this.feishu = new FeishuConfig();
        this.dingtalk = new DingTalkConfig();
        this.qq = new QQConfig();
        this.maixcam = new MaixCamConfig();
    }
    
    // Getter 和 Setter 方法
    public TelegramConfig getTelegram() {
        return telegram;
    }
    
    public void setTelegram(TelegramConfig telegram) {
        this.telegram = telegram;
    }
    
    public DiscordConfig getDiscord() {
        return discord;
    }
    
    public void setDiscord(DiscordConfig discord) {
        this.discord = discord;
    }
    
    public WhatsAppConfig getWhatsapp() {
        return whatsapp;
    }
    
    public void setWhatsapp(WhatsAppConfig whatsapp) {
        this.whatsapp = whatsapp;
    }
    
    public FeishuConfig getFeishu() {
        return feishu;
    }
    
    public void setFeishu(FeishuConfig feishu) {
        this.feishu = feishu;
    }
    
    public DingTalkConfig getDingtalk() {
        return dingtalk;
    }
    
    public void setDingtalk(DingTalkConfig dingtalk) {
        this.dingtalk = dingtalk;
    }
    
    public QQConfig getQq() {
        return qq;
    }
    
    public void setQq(QQConfig qq) {
        this.qq = qq;
    }
    
    public MaixCamConfig getMaixcam() {
        return maixcam;
    }
    
    public void setMaixcam(MaixCamConfig maixcam) {
        this.maixcam = maixcam;
    }
    
    // 内部配置类
    
    public static class TelegramConfig {
        private boolean enabled;
        private String token;
        private List<String> allowFrom;
        
        public TelegramConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getToken() {
            return token;
        }
        
        public void setToken(String token) {
            this.token = token;
        }
        
        public List<String> getAllowFrom() {
            return allowFrom;
        }
        
        public void setAllowFrom(List<String> allowFrom) {
            this.allowFrom = allowFrom;
        }
    }
    
    public static class DiscordConfig {
        private boolean enabled;
        private String token;
        private List<String> allowFrom;
        
        public DiscordConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getToken() {
            return token;
        }
        
        public void setToken(String token) {
            this.token = token;
        }
        
        public List<String> getAllowFrom() {
            return allowFrom;
        }
        
        public void setAllowFrom(List<String> allowFrom) {
            this.allowFrom = allowFrom;
        }
    }
    
    public static class WhatsAppConfig {
        private boolean enabled;
        private String bridgeUrl;
        private List<String> allowFrom;
        
        public WhatsAppConfig() {
            this.enabled = false;
            this.bridgeUrl = "ws://localhost:3001";
            this.allowFrom = new ArrayList<>();
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getBridgeUrl() {
            return bridgeUrl;
        }
        
        public void setBridgeUrl(String bridgeUrl) {
            this.bridgeUrl = bridgeUrl;
        }
        
        public List<String> getAllowFrom() {
            return allowFrom;
        }
        
        public void setAllowFrom(List<String> allowFrom) {
            this.allowFrom = allowFrom;
        }
    }
    
    public static class FeishuConfig {
        private boolean enabled;
        private String appId;
        private String appSecret;
        private String encryptKey;
        private String verificationToken;
        private List<String> allowFrom;
        
        public FeishuConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getAppId() {
            return appId;
        }
        
        public void setAppId(String appId) {
            this.appId = appId;
        }
        
        public String getAppSecret() {
            return appSecret;
        }
        
        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }
        
        public String getEncryptKey() {
            return encryptKey;
        }
        
        public void setEncryptKey(String encryptKey) {
            this.encryptKey = encryptKey;
        }
        
        public String getVerificationToken() {
            return verificationToken;
        }
        
        public void setVerificationToken(String verificationToken) {
            this.verificationToken = verificationToken;
        }
        
        public List<String> getAllowFrom() {
            return allowFrom;
        }
        
        public void setAllowFrom(List<String> allowFrom) {
            this.allowFrom = allowFrom;
        }
    }
    
    public static class DingTalkConfig {
        private boolean enabled;
        private String clientId;
        private String clientSecret;
        private String webhook;
        private List<String> allowFrom;
        
        public DingTalkConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getClientId() {
            return clientId;
        }
        
        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
        
        public String getClientSecret() {
            return clientSecret;
        }
        
        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
        
        public String getWebhook() {
            return webhook;
        }
        
        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }
        
        public List<String> getAllowFrom() {
            return allowFrom;
        }
        
        public void setAllowFrom(List<String> allowFrom) {
            this.allowFrom = allowFrom;
        }
    }
    
    public static class QQConfig {
        private boolean enabled;
        private String appId;
        private String appSecret;
        private List<String> allowFrom;
        
        public QQConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getAppId() {
            return appId;
        }
        
        public void setAppId(String appId) {
            this.appId = appId;
        }
        
        public String getAppSecret() {
            return appSecret;
        }
        
        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }
        
        public List<String> getAllowFrom() {
            return allowFrom;
        }
        
        public void setAllowFrom(List<String> allowFrom) {
            this.allowFrom = allowFrom;
        }
    }
    
    public static class MaixCamConfig {
        private boolean enabled;
        private String host;
        private int port;
        private List<String> allowFrom;
        
        public MaixCamConfig() {
            this.enabled = false;
            this.host = "0.0.0.0";
            this.port = 18790;
            this.allowFrom = new ArrayList<>();
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getHost() {
            return host;
        }
        
        public void setHost(String host) {
            this.host = host;
        }
        
        public int getPort() {
            return port;
        }
        
        public void setPort(int port) {
            this.port = port;
        }
        
        public List<String> getAllowFrom() {
            return allowFrom;
        }
        
        public void setAllowFrom(List<String> allowFrom) {
            this.allowFrom = allowFrom;
        }
    }
}
