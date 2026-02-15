package io.leavesfly.tinyclaw.voice;

/**
 * 语音转录服务接口
 * 
 * 定义了语音转文字服务的统一契约，当前支持：
 * - AliyunTranscriber：阿里云语音识别服务（DashScope Paraformer）
 * 
 * 典型使用流程：
 * 1. 初始化转录器实例（传入 API 密钥）
 * 2. 调用 isAvailable() 检查服务是否可用
 * 3. 调用 transcribe() 转录音频文件
 * 
 * @see AliyunTranscriber
 */
public interface Transcriber {
    
    /**
     * 转录音频文件
     * 
     * 将音频文件内容转换为文本。支持的音频格式取决于具体实现，
     * 常见格式包括：mp3, wav, ogg, m4a, flac 等。
     * 
     * @param audioFilePath 音频文件的本地路径
     * @return 转录结果，包含转录文本、语言和时长等信息
     * @throws Exception 转录失败时抛出异常
     */
    TranscriptionResult transcribe(String audioFilePath) throws Exception;
    
    /**
     * 检查转录器是否可用
     * 
     * 判断转录服务是否已正确配置且可以使用。
     * 通常检查 API 密钥是否已配置。
     * 
     * @return true 如果服务可用，false 如果服务不可用
     */
    boolean isAvailable();
    
    /**
     * 获取服务提供商名称
     * 
     * @return 服务提供商名称，如 "aliyun"、"groq"
     */
    String getProviderName();
    
    /**
     * 转录结果
     */
    class TranscriptionResult {
        private String text;
        private String language;
        private Double duration;
        
        public TranscriptionResult() {}
        
        public TranscriptionResult(String text) {
            this.text = text;
        }
        
        public TranscriptionResult(String text, String language, Double duration) {
            this.text = text;
            this.language = language;
            this.duration = duration;
        }
        
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        
        public Double getDuration() { return duration; }
        public void setDuration(Double duration) { this.duration = duration; }
    }
}
