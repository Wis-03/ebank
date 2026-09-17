package net.youssfi.enstbot.telegram;

import jakarta.annotation.PostConstruct;
import net.youssfi.enstbot.agents.AIAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class TelegramBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);
    private static final Duration REGISTRATION_RETRY_DELAY = Duration.ofSeconds(30);

    @Value("${telegram.api.key}")
    private String telegramBotToken;
    private AIAgent aiAgent;
    private OpenAiAudioTranscriptionModel transcriptionModel;

    public TelegramBot(AIAgent aiAgent, OpenAiAudioTranscriptionModel transcriptionModel) {
        this.aiAgent = aiAgent;
        this.transcriptionModel = transcriptionModel;
    }
    @PostConstruct
    public void registerTelegramBot(){
        // Token fourni par la variable TELEGRAM_BOT_TOKEN : sans token, le chatbot demarre sans Telegram.
        if (telegramBotToken == null || telegramBotToken.isBlank()) {
            log.warn("TELEGRAM_BOT_TOKEN absent : bot Telegram non enregistre");
            return;
        }
        if (!tryRegister()) {
            // Telegram injoignable au demarrage : le chatbot (API web) demarre quand meme, l'enregistrement est retente.
            Thread.ofVirtual().name("telegram-registration").start(() -> {
                while (!tryRegister()) {
                    try {
                        Thread.sleep(REGISTRATION_RETRY_DELAY);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }
    }

    private boolean tryRegister() {
        try {
            new TelegramBotsApi(DefaultBotSession.class).registerBot(this);
            log.info("Bot Telegram enregistre (long polling)");
            return true;
        } catch (TelegramApiException e) {
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            log.warn("Enregistrement Telegram impossible ({}), nouvel essai dans {} s",
                    cause.getMessage(), REGISTRATION_RETRY_DELAY.toSeconds());
            return false;
        }
    }

    @Override
    public void onUpdateReceived(Update telegraRequest) {
        if(!telegraRequest.hasMessage()) return;
        Long chatId = telegraRequest.getMessage().getChatId();
        try {
            String messageText = telegraRequest.getMessage().getText();
            if (telegraRequest.getMessage().hasVoice()) {
                messageText = transcribeVoice(telegraRequest.getMessage().getVoice());
            }
            List<PhotoSize> photos = telegraRequest.getMessage().getPhoto();
            List<Media> mediaList = new ArrayList<>();
            String caption =null;
            if (photos!=null){
                caption = telegraRequest.getMessage().getCaption();
                if (caption==null) caption="Décris cette image.";

                // Telegram envoie la meme photo en plusieurs tailles : seule la plus grande est transmise.
                // Les photos Telegram sont en JPEG ; le fichier est telecharge par l'API du bot (aucune URL avec token).
                PhotoSize largest = photos.get(photos.size() - 1);
                GetFile getFile = new GetFile();
                getFile.setFileId(largest.getFileId());
                File file = execute(getFile);
                byte[] image;
                try (InputStream in = downloadFileAsStream(file)) {
                    image = in.readAllBytes();
                }
                mediaList.add(Media.builder()
                        .id(largest.getFileId())
                        .mimeType(MimeTypeUtils.IMAGE_JPEG)
                        .data(new ByteArrayResource(image))
                        .build());
                log.info("Telegram photo : {}x{}, {} octets", largest.getWidth(), largest.getHeight(), image.length);
            }
            String query = messageText!=null?messageText:caption;
            if (query == null || query.isBlank()) {
                sendTextMessage(chatId, "Je peux traiter les messages texte, les photos et les notes vocales.");
                return;
            }
            UserMessage userMessage = UserMessage.builder()
                    .text(query)
                    .media(mediaList)
                    .build();
            sendTypingQuestion(chatId);
            String answer = aiAgent.askAgent(new Prompt(userMessage));
            sendTextMessage(chatId, answer);
        } catch (Exception e) {
            log.error("Telegram : echec du traitement du message ({})", e.getClass().getSimpleName(), e);
            try {
                sendTextMessage(chatId, "Désolé, une erreur est survenue lors du traitement de votre message. Veuillez réessayer.");
            } catch (TelegramApiException ignored) {
                // Telegram injoignable : l'erreur d'origine est deja journalisee.
            }
        }
    }

    @Override
    public String getBotUsername() {
        return "ENSETAIBot";
    }

    @Override
    public String getBotToken() {
        return telegramBotToken;
    }

    /**
     * Note vocale Telegram -> speech-to-text (Whisper) -> texte transmis a l'agent.
     * Le fichier est telecharge par l'API du bot : l'URL contenant le token n'est ni construite ni loggee.
     */
    private String transcribeVoice(Voice voice) throws Exception {
        GetFile getFile = new GetFile();
        getFile.setFileId(voice.getFileId());
        File file = execute(getFile);
        byte[] audio;
        try (InputStream in = downloadFileAsStream(file)) {
            audio = in.readAllBytes();
        }
        // OpenAI deduit le format du nom de fichier ; l'extension reelle (.oga) est acceptee.
        String path = file.getFilePath();
        String filename = "voice" + (path != null && path.contains(".") ? path.substring(path.lastIndexOf('.')) : ".ogg");
        ByteArrayResource resource = new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        String transcript = transcriptionModel.call(new AudioTranscriptionPrompt(resource)).getResult().getOutput();
        log.info("Telegram voice : {} s, {} octets -> transcription de {} caracteres", voice.getDuration(), audio.length, transcript.length());
        return transcript;
    }

    private void sendTextMessage(long chatId, String text) throws TelegramApiException {
        SendMessage sendMessage = new SendMessage(String.valueOf(chatId), text);
        execute(sendMessage);
    }
    private void sendTypingQuestion(long chatId) throws TelegramApiException {
        SendChatAction sendChatAction = new SendChatAction();
        sendChatAction.setChatId(String.valueOf(chatId));
        sendChatAction.setAction(ActionType.TYPING);
        execute(sendChatAction);
    }
}
