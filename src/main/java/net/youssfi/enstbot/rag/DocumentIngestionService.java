package net.youssfi.enstbot.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Pipeline d'ingestion du RAG :
 * PDF -> PagePdfDocumentReader -> TokenTextSplitter (chunks) -> embeddings OpenAI -> PGVector.
 *
 * Idempotence : chaque chunk porte en metadonnees le nom du fichier source et l'empreinte
 * SHA-256 du fichier. Un PDF deja indexe avec la meme empreinte n'est pas re-traite ; un PDF
 * modifie voit ses anciens chunks supprimes puis re-indexes.
 */
@Component
public class DocumentIngestionService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final String docsLocation;
    private final int chunkSize;
    private final String table;

    public DocumentIngestionService(VectorStore vectorStore,
                                    JdbcTemplate jdbcTemplate,
                                    @Value("${rag.docs.location}") String docsLocation,
                                    @Value("${rag.chunk-size}") int chunkSize,
                                    @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schema,
                                    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String table) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.docsLocation = docsLocation;
        this.chunkSize = chunkSize;
        this.table = schema + "." + table;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] pdfs = new PathMatchingResourcePatternResolver().getResources(docsLocation);
        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(chunkSize).build();
        int indexed = 0;
        int upToDate = 0;

        for (Resource pdf : pdfs) {
            String source = pdf.getFilename();
            String checksum = sha256(pdf);

            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE metadata->>'source' = ? AND metadata->>'checksum' = ?",
                    Integer.class, source, checksum);
            if (existing != null && existing > 0) {
                log.info("RAG ingestion : {} deja indexe ({} chunk(s)), embeddings non recalcules", source, existing);
                upToDate++;
                continue;
            }

            jdbcTemplate.update("DELETE FROM " + table + " WHERE metadata->>'source' = ?", source);

            List<Document> pages = new PagePdfDocumentReader(pdf).get().stream()
                    .map(page -> {
                        Map<String, Object> metadata = new HashMap<>(page.getMetadata());
                        metadata.put("source", source);
                        metadata.put("checksum", checksum);
                        return new Document(page.getText(), metadata);
                    })
                    .toList();
            List<Document> chunks = splitter.apply(pages);
            vectorStore.add(chunks);

            log.info("RAG ingestion : {} -> {} page(s), {} chunk(s) indexe(s)", source, pages.size(), chunks.size());
            indexed++;
        }

        Integer vectors = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        log.info("RAG ingestion terminee : {} PDF trouve(s), {} indexe(s), {} deja a jour, {} vecteur(s) dans {}",
                pdfs.length, indexed, upToDate, vectors, table);
    }

    private static String sha256(Resource resource) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = resource.getInputStream()) {
            return HexFormat.of().formatHex(digest.digest(in.readAllBytes()));
        }
    }
}
