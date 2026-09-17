package net.youssfi.enstbot.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaTypeFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Multimodal RAG, chaine audio :
 * Audio -> Whisper (speech-to-text) -> transcription -> TokenTextSplitter -> text-embedding-3-small -> PGVector.
 *
 * Meme table que les PDF et les images. Idempotence par empreinte SHA-256 du fichier.
 */
@Component
public class AudioIngestionService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AudioIngestionService.class);

    private final OpenAiAudioTranscriptionModel transcriptionModel;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final String audioLocation;
    private final String transcriptionModelName;
    private final int chunkSize;
    private final String table;

    public AudioIngestionService(OpenAiAudioTranscriptionModel transcriptionModel,
                                 VectorStore vectorStore,
                                 JdbcTemplate jdbcTemplate,
                                 @Value("${rag.audio.location}") String audioLocation,
                                 @Value("${spring.ai.openai.audio.transcription.options.model}") String transcriptionModelName,
                                 @Value("${rag.chunk-size}") int chunkSize,
                                 @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schema,
                                 @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String table) {
        this.transcriptionModel = transcriptionModel;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.audioLocation = audioLocation;
        this.transcriptionModelName = transcriptionModelName;
        this.chunkSize = chunkSize;
        this.table = schema + "." + table;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] audios = new PathMatchingResourcePatternResolver().getResources(audioLocation);
        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(chunkSize).build();
        int indexed = 0;
        int upToDate = 0;

        for (Resource audio : audios) {
            String source = audio.getFilename();
            byte[] bytes = audio.getContentAsByteArray();
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE metadata->>'source' = ? AND metadata->>'checksum' = ?",
                    Integer.class, source, checksum);
            if (existing != null && existing > 0) {
                log.info("RAG audio : {} deja indexe ({} chunk(s)), transcription non recalculee", source, existing);
                upToDate++;
                continue;
            }

            jdbcTemplate.update("DELETE FROM " + table + " WHERE metadata->>'source' = ?", source);

            String transcript = transcriptionModel.call(new AudioTranscriptionPrompt(audio)).getResult().getOutput();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", source);
            metadata.put("type", "audio");
            metadata.put("mime_type", MediaTypeFactory.getMediaType(audio).map(Object::toString).orElse("audio/wav"));
            metadata.put("resource_path", ImageIngestionService.resourcePath(audioLocation, source));
            metadata.put("checksum", checksum);
            metadata.put("transcription_model", transcriptionModelName);
            Double duration = durationSeconds(bytes);
            if (duration != null) {
                metadata.put("duration", duration);
            }

            List<Document> chunks = splitter.apply(List.of(new Document(transcript, metadata)));
            vectorStore.add(chunks);

            log.info("RAG audio : {} -> transcription {} ({} caracteres) -> {} chunk(s) indexe(s)",
                    source, transcriptionModelName, transcript.length(), chunks.size());
            indexed++;
        }

        log.info("RAG audio termine : {} fichier(s) trouve(s), {} indexe(s), {} deja a jour",
                audios.length, indexed, upToDate);
    }

    /** Duree en secondes pour les formats lus par javax.sound (WAV) ; null sinon. */
    private static Double durationSeconds(byte[] bytes) {
        try {
            AudioFileFormat format = AudioSystem.getAudioFileFormat(new BufferedInputStream(new ByteArrayInputStream(bytes)));
            if (format.getFrameLength() > 0 && format.getFormat().getFrameRate() > 0) {
                return Math.round(format.getFrameLength() / format.getFormat().getFrameRate() * 10) / 10.0;
            }
        } catch (Exception e) {
            log.debug("Duree audio non determinable : {}", e.getMessage());
        }
        return null;
    }
}
