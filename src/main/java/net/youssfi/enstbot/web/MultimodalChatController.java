package net.youssfi.enstbot.web;

import net.youssfi.enstbot.agents.AIAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Entrees multimodales pour l'interface web, en complement de GET /chat (texte).
 * Aucune nouvelle logique IA : chaque endpoint prepare le message comme le fait deja TelegramBot
 * (image -> Media, audio -> Whisper -> texte) puis le transmet au meme AIAgent (memoire, ReAct, MCP, RAG).
 */
@RestController
public class MultimodalChatController {
    private static final Logger log = LoggerFactory.getLogger(MultimodalChatController.class);
    // Borne le texte extrait d'un PDF transmis au LLM (fenetre de contexte et cout).
    private static final int PDF_TEXT_LIMIT = 20000;

    /** transcription n'est renseignee que pour l'audio. */
    public record ChatAnswer(String answer, String transcription) {
    }

    private final AIAgent aiAgent;
    private final OpenAiAudioTranscriptionModel transcriptionModel;

    public MultimodalChatController(AIAgent aiAgent, OpenAiAudioTranscriptionModel transcriptionModel) {
        this.aiAgent = aiAgent;
        this.transcriptionModel = transcriptionModel;
    }

    /** Image + question : l'image est jointe au message, comme une photo Telegram. */
    @PostMapping(value = "/chat/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAnswer image(@RequestPart("file") MultipartFile file,
                            @RequestParam(name = "query", required = false) String query) throws IOException {
        String contentType = requireType(file, "image/");
        String text = hasText(query) ? query : "Décris cette image.";
        UserMessage message = UserMessage.builder()
                .text(text)
                .media(Media.builder()
                        .mimeType(MimeType.valueOf(contentType))
                        .data(new ByteArrayResource(file.getBytes()))
                        .name(file.getOriginalFilename())
                        .build())
                .build();
        log.info("Web image : {} ({}, {} octets)", file.getOriginalFilename(), contentType, file.getSize());
        return new ChatAnswer(aiAgent.askAgent(new Prompt(message)), null);
    }

    /** Audio : speech-to-text Whisper, puis la transcription est traitee comme une question texte. */
    @PostMapping(value = "/chat/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAnswer audio(@RequestPart("file") MultipartFile file) throws IOException {
        requireType(file, "audio/", "video/webm");
        // OpenAI deduit le format audio de l'extension du nom de fichier.
        String filename = hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "audio.webm";
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        String transcription = transcriptionModel.call(new AudioTranscriptionPrompt(resource)).getResult().getOutput();
        log.info("Web audio : {} ({} octets) -> transcription de {} caracteres", filename, file.getSize(), transcription.length());
        if (!hasText(transcription)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucune parole détectée dans l'audio.");
        }
        return new ChatAnswer(aiAgent.askAgent(new Prompt(transcription)), transcription);
    }

    /** PDF + question : le texte du document est extrait puis joint a la question. */
    @PostMapping(value = "/chat/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAnswer pdf(@RequestPart("file") MultipartFile file,
                          @RequestParam(name = "query", required = false) String query) throws IOException {
        requireType(file, "application/pdf");
        String name = hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document.pdf";
        String content = new PagePdfDocumentReader(new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return name;
            }
        }).get().stream().map(Document::getText).collect(Collectors.joining("\n"));
        if (!hasText(content)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucun texte lisible dans ce PDF.");
        }
        boolean truncated = content.length() > PDF_TEXT_LIMIT;
        String text = (hasText(query) ? query : "Résume ce document.")
                + "\n\nContenu du document PDF « " + name + " » fourni par l'utilisateur"
                + (truncated ? " (tronqué)" : "") + " :\n"
                + (truncated ? content.substring(0, PDF_TEXT_LIMIT) : content);
        log.info("Web PDF : {} ({} octets) -> {} caracteres extraits{}", name, file.getSize(), content.length(),
                truncated ? ", tronques a " + PDF_TEXT_LIMIT : "");
        return new ChatAnswer(aiAgent.askAgent(new Prompt(text)), null);
    }

    private static String requireType(MultipartFile file, String... acceptedPrefixes) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier manquant ou vide.");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        for (String prefix : acceptedPrefixes) {
            if (contentType.startsWith(prefix)) {
                return contentType;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Type de fichier non accepté : " + contentType);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
