package io.leavesfly.tinyclaw.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Groq voice transcription service (using Whisper API)
 */
public class GroqTranscriber {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("voice");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String API_BASE = "https://api.groq.com/openai/v1";
    
    private final String apiKey;
    private final OkHttpClient httpClient;
    
    public GroqTranscriber(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        
        logger.debug("Creating Groq transcriber", Map.of("has_api_key", apiKey != null && !apiKey.isEmpty()));
    }
    
    /**
     * Transcribe an audio file
     */
    public TranscriptionResponse transcribe(String audioFilePath) throws Exception {
        logger.info("Starting transcription", Map.of("audio_file", audioFilePath));
        
        File audioFile = new File(audioFilePath);
        if (!audioFile.exists()) {
            throw new IOException("Audio file not found: " + audioFilePath);
        }
        
        logger.debug("Audio file details", Map.of(
                "size_bytes", audioFile.length(),
                "file_name", audioFile.getName()
        ));
        
        // 构建 multipart request
        RequestBody fileBody = RequestBody.create(audioFile, MediaType.parse("audio/*"));
        
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("response_format", "json")
                .build();
        
        String url = API_BASE + "/audio/transcriptions";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
        
        logger.debug("Sending transcription request to Groq API", Map.of(
                "url", url,
                "file_size_bytes", audioFile.length()
        ));
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("API error", Map.of(
                        "status_code", response.code(),
                        "response", responseBody
                ));
                throw new IOException("API error (status " + response.code() + "): " + responseBody);
            }
            
            logger.debug("Received response from Groq API", Map.of(
                    "status_code", response.code(),
                    "response_size_bytes", responseBody.length()
            ));
            
            TranscriptionResponse result = objectMapper.readValue(responseBody, TranscriptionResponse.class);
            
            logger.info("Transcription completed successfully", Map.of(
                    "text_length", result.getText() != null ? result.getText().length() : 0,
                    "language", result.getLanguage() != null ? result.getLanguage() : "",
                    "duration_seconds", result.getDuration()
            ));
            
            return result;
        }
    }
    
    /**
     * 检查 if transcriber is available
     */
    public boolean isAvailable() {
        boolean available = apiKey != null && !apiKey.isEmpty();
        logger.debug("Checking transcriber availability", Map.of("available", available));
        return available;
    }
    
    /**
     * Transcription response
     */
    public static class TranscriptionResponse {
        private String text;
        private String language;
        private Double duration;
        
        public TranscriptionResponse() {}
        
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        
        public Double getDuration() { return duration; }
        public void setDuration(Double duration) { this.duration = duration; }
    }
}
