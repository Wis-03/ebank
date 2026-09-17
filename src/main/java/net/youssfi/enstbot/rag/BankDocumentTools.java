package net.youssfi.enstbot.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Outil RAG local de l'agent : Query -> embedding -> recherche de similarite PGVector -> Context.
 * Il est fourni au LLM au meme titre que les outils MCP ; c'est le LLM qui decide de l'appeler.
 */
@Component
public class BankDocumentTools {
    private static final Logger log = LoggerFactory.getLogger(BankDocumentTools.class);
    static final String NO_RESULT = "Information non disponible dans la base documentaire.";

    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;

    public BankDocumentTools(VectorStore vectorStore,
                             @Value("${rag.search.top-k}") int topK,
                             @Value("${rag.search.similarity-threshold}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    @Tool(name = "searchBankDocuments", description = """
            Semantic search in the bank's documentation base, which contains three kinds of sources: \
            PDF documents (conditions of the current account CURRENT-ACCOUNT and of the savings \
            account SAVING-ACCOUNT, fee schedule, account opening and security), semantic \
            descriptions of informational images (.png, e.g. posters, card benefits, app procedures) \
            and transcriptions of audio announcements (.wav). Each result states its source file. \
            Use it for any question about rules, conditions, rates, limits, fees, opening hours, \
            services or procedures. It contains no customer or account data.""")
    public String searchBankDocuments(
            @ToolParam(description = "the question or keywords to search for, in French") String query) {
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build());

        log.info("RAG searchBankDocuments(\"{}\") -> {} chunk(s) {}", query, results.size(),
                results.stream().map(d -> d.getMetadata().get("source") + " score=" + score(d)).toList());

        if (results.isEmpty()) {
            return NO_RESULT;
        }
        return results.stream()
                .map(d -> "[Source : %s | page %s | similarite %s]%n%s".formatted(
                        d.getMetadata().get("source"),
                        d.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER),
                        score(d),
                        d.getText()))
                .collect(Collectors.joining("\n\n"));
    }

    private static String score(Document document) {
        return document.getScore() == null ? "n/a" : String.format(Locale.ROOT, "%.3f", document.getScore());
    }
}
