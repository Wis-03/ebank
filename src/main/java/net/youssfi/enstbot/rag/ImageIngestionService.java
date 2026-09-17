package net.youssfi.enstbot.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaTypeFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Multimodal RAG, chaine image :
 * Image -> GPT-4o Vision -> description semantique fidele -> text-embedding-3-small -> PGVector.
 *
 * L'image n'est pas vectorisee directement (OpenAI ne fournit pas d'embedding d'image) :
 * c'est sa description textuelle qui est stockee et vectorisee, dans la meme table que les PDF.
 * Idempotence identique aux PDF : empreinte SHA-256 du fichier en metadonnees.
 */
@Component
public class ImageIngestionService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ImageIngestionService.class);

    private static final String DESCRIPTION_PROMPT = """
            Décris cette image de façon très fidèle, en français, pour une base documentaire bancaire.
            Découpe ta description en sections thématiques : une section par zone d'information de
            l'image (par exemple : horaires, services, contact, procédure, avantages).
            - Commence chaque section par une ligne "### <titre de la section>".
            - Chaque section doit être compréhensible seule : rappelle le nom de l'organisation et le
              sujet de la zone, puis recopie mot pour mot TOUT le texte visible de cette zone (titres,
              libellés, valeurs, montants, horaires, étapes, mentions), en conservant exactement les
              nombres, unités et symboles.
            - Termine par une section "### Structure de l'image" qui décrit brièvement sa mise en page.
            N'ajoute aucune information qui n'est pas visible dans l'image.
            """;
    private static final String SECTION_MARKER = "### ";

    private final ChatClient visionClient;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final String imagesLocation;
    private final String descriptionModel;
    private final String table;

    public ImageIngestionService(ChatModel chatModel,
                                 VectorStore vectorStore,
                                 JdbcTemplate jdbcTemplate,
                                 @Value("${rag.images.location}") String imagesLocation,
                                 @Value("${rag.images.description-model}") String descriptionModel,
                                 @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schema,
                                 @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String table) {
        // Client dedie a la description : sans outils, sans memoire, independant de l'agent.
        this.visionClient = ChatClient.create(chatModel);
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.imagesLocation = imagesLocation;
        this.descriptionModel = descriptionModel;
        this.table = schema + "." + table;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] images = new PathMatchingResourcePatternResolver().getResources(imagesLocation);
        int indexed = 0;
        int upToDate = 0;

        for (Resource image : images) {
            String source = image.getFilename();
            byte[] bytes = image.getContentAsByteArray();
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE metadata->>'source' = ? AND metadata->>'checksum' = ?",
                    Integer.class, source, checksum);
            if (existing != null && existing > 0) {
                log.info("RAG image : {} deja indexee, description non recalculee", source);
                upToDate++;
                continue;
            }

            jdbcTemplate.update("DELETE FROM " + table + " WHERE metadata->>'source' = ?", source);

            MimeType mimeType = MediaTypeFactory.getMediaType(image).map(m -> (MimeType) m).orElse(MimeTypeUtils.IMAGE_PNG);
            String description = visionClient.prompt()
                    .options(OpenAiChatOptions.builder().model(descriptionModel).temperature(0.0).build())
                    .user(u -> u.text(DESCRIPTION_PROMPT).media(mimeType, image))
                    .call()
                    .content();

            Map<String, Object> metadata = new HashMap<>(Map.of(
                    "source", source,
                    "type", "image",
                    "mime_type", mimeType.toString(),
                    "resource_path", resourcePath(imagesLocation, source),
                    "checksum", checksum,
                    "description_model", descriptionModel));
            // Une section thematique = un vecteur : sur une image riche (horaires + services + contact),
            // un vecteur unique dilue chaque information et la fait passer sous le seuil de similarite.
            List<String> sections = sections(description);
            List<Document> chunks = new ArrayList<>();
            for (int i = 0; i < sections.size(); i++) {
                Map<String, Object> chunkMetadata = new HashMap<>(metadata);
                chunkMetadata.put("section_index", i);
                chunks.add(new Document(sections.get(i), chunkMetadata));
            }
            vectorStore.add(chunks);

            log.info("RAG image : {} -> description {} ({} caracteres) -> {} section(s) indexee(s)",
                    source, descriptionModel, description.length(), chunks.size());
            indexed++;
        }

        log.info("RAG images terminees : {} image(s) trouvee(s), {} indexee(s), {} deja a jour",
                images.length, indexed, upToDate);
    }

    /** Decoupe la description sur les lignes "### " ; sans section, la description entiere reste un seul chunk. */
    static List<String> sections(String description) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : description.split("\\R")) {
            if (line.startsWith(SECTION_MARKER) && !current.isEmpty()) {
                sections.add(current.toString().strip());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (!current.toString().isBlank()) {
            sections.add(current.toString().strip());
        }
        return sections;
    }

    static String resourcePath(String location, String filename) {
        String path = location.replaceFirst("^classpath\\*?:", "");
        return path.substring(0, path.lastIndexOf('/') + 1) + filename;
    }
}
