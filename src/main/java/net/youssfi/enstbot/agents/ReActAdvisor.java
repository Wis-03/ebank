package net.youssfi.enstbot.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cycle ReAct explicite et borne : GOAL -> (REASONING -> ACTION -> OBSERVATION)* -> FINAL ANSWER.
 *
 * Integration Spring AI : meme principe que ToolCallAdvisor de Spring AI 1.1.0-M4, mais avec Goal,
 * etapes tracees, limite d'iterations et condition d'arret controlee.
 * - l'execution interne des outils par OpenAiChatModel est desactivee pour ces appels, ce qui evite
 *   toute double boucle ;
 * - chaque Action passe par le ToolCallingManager de Spring AI, donc par les memes ToolCallback :
 *   SyncMcpToolCallback -> serveurs MCP, et searchBankDocuments pour le RAG ;
 * - l'advisor est place a l'interieur de MessageChatMemoryAdvisor : la memoire encadre toute la
 *   boucle et n'enregistre que la question et la reponse finale, comme avant.
 */
@Component
public class ReActAdvisor implements CallAdvisor {
    private static final Logger log = LoggerFactory.getLogger(ReActAdvisor.class);
    private static final int LOG_LIMIT = 220;
    // L'evaluateur doit voir tous les chunks renvoyes par searchBankDocuments (top-k 4, chunks jusqu'a ~2200 caracteres) :
    // une limite plus courte masquait les derniers resultats et bloquait le Goal jusqu'a MAX_ITERATIONS.
    private static final int PROMPT_OBSERVATION_LIMIT = 12000;

    private static final String GOAL_INSTRUCTIONS = """
            Tu définis l'objectif opérationnel (Goal) d'un agent bancaire pour UNE requête utilisateur.
            - description : une phrase commençant par un verbe à l'infinitif, qui précise l'objet
              concerné (identifiant de compte, type de compte, sujet documentaire...).
            - successCriteria : uniquement les informations que l'utilisateur demande EXPLICITEMENT et
              que les outils disponibles (liste fournie) peuvent obtenir. N'ajoute aucune exigence non
              demandée. Pour une demande générale d'« informations » sur un objet, le critère est
              l'obtention des données que l'outil correspondant retourne pour cet objet.
            - Si la requête contient un document fourni par l'utilisateur (texte d'un PDF joint) ou si une
              image est jointe, ce contenu est une source valide : le critère porte alors sur les
              informations lues dans ce contenu, sans outil. Ne demande jamais de rechercher ce document
              dans la base documentaire.
            Ne réponds pas à la requête et n'invente aucune donnée.
            """;

    private static final String EVALUATION_INSTRUCTIONS = """
            Tu évalues si le Goal d'un agent ReAct est atteint. Deux sources seulement sont valides :
            1. les observations (résultats réels des outils) ;
            2. le contenu fourni par l'utilisateur dans sa requête : texte d'un document PDF joint, ou
               image jointe (visible dans ce message).
            Choisis un verdict :
            - ACHIEVED : les sources valides contiennent toutes les informations exigées par le critère
              de réussite. Une donnée du système bancaire (compte, solde, client, règle de la banque)
              n'est établie que par une observation ; une information lue dans le contenu fourni par
              l'utilisateur est établie lorsque la question porte sur ce contenu. Une salutation ou une
              demande sans donnée à récupérer est ACHIEVED dès qu'une réponse candidate y répond.
            - UNAVAILABLE : une observation montre que l'information exigée n'existe pas (par exemple
              "not found" ou "Information non disponible dans la base documentaire."), ou l'information
              manquante ne figure pas dans le contenu fourni et ne peut être fournie par aucun des
              outils disponibles (liste fournie).
            - IN_PROGRESS : il manque une information absente du contenu fourni qu'un des outils
              disponibles peut encore fournir.
            justification : une phrase courte, en français.
            """;

    /** Verdict explicite de l'evaluation du Goal. */
    record GoalEvaluation(Verdict verdict, String justification) {
        enum Verdict { ACHIEVED, IN_PROGRESS, UNAVAILABLE }
    }

    private final ToolCallingManager toolCallingManager;
    private final ChatClient controlClient;
    private final int maxIterations;

    public ReActAdvisor(ToolCallingManager toolCallingManager,
                        ChatModel chatModel,
                        @Value("${react.max-iterations:5}") int maxIterations) {
        this.toolCallingManager = toolCallingManager;
        // Client de controle (Goal, evaluation) : sans outils ni memoire, independant de l'agent.
        this.controlClient = ChatClient.create(chatModel);
        this.maxIterations = Math.max(1, maxIterations);
    }

    @Override
    public String getName() {
        return "ReActAdvisor";
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatOptions options = request.prompt().getOptions().copy();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            toolOptions.setInternalToolExecutionEnabled(false);
        }
        String toolCatalog = toolCatalog(options);

        String userQuery = lastUserText(request.prompt());
        // Image jointe (interface web ou Telegram) : transmise au Goal et a l'evaluateur, qui doivent la voir.
        List<Media> userMedia = lastUserMedia(request.prompt());
        ReActContext context = new ReActContext(defineGoal(userQuery, userMedia, toolCatalog), userQuery);
        log.info("[REACT] Goal created : {} | critere : {}",
                context.goal().description(), context.goal().successCriteria());
        List<Message> conversation = new ArrayList<>(request.prompt().getInstructions());
        conversation.add(Math.max(0, conversation.size() - 1), new SystemMessage(reactInstructions(context.goal())));

        ChatClientResponse lastResponse = null;
        while (context.status() == ReActContext.GoalStatus.IN_PROGRESS && context.iteration() < maxIterations) {
            int iteration = context.nextIteration();
            log.info("[REACT] Iteration {}/{}", iteration, maxIterations);

            lastResponse = chain.copy(this).nextCall(request.mutate().prompt(new Prompt(conversation, options)).build());
            ChatResponse chatResponse = lastResponse.chatResponse();
            AssistantMessage assistant = chatResponse.getResult().getOutput();

            if (!chatResponse.hasToolCalls()) {
                log.info("[REACT] Aucune action : reponse candidate du modele");
                GoalEvaluation evaluation = evaluateGoal(context, userMedia, toolCatalog, assistant.getText());
                applyEvaluation(context, evaluation);
                if (context.status() == ReActContext.GoalStatus.IN_PROGRESS) {
                    conversation.add(assistant);
                    conversation.add(new UserMessage("[REACT] Objectif non encore atteint : " + evaluation.justification()
                            + " Utilise les outils pour obtenir l'information manquante, ou indique qu'elle est indisponible."));
                } else {
                    context.setFinalAnswer(assistant.getText());
                }
                continue;
            }

            List<String> actions = assistant.getToolCalls().stream()
                    .map(call -> call.name() + "(" + call.arguments() + ")")
                    .toList();
            String reasoning = justification(assistant.getText(), actions);
            log.info("[REACT] Reasoning : {}", reasoning);
            actions.forEach(action -> log.info("[REACT] Action : {}", abbreviate(action)));

            ToolExecutionResult result = toolCallingManager.executeToolCalls(new Prompt(conversation, options), chatResponse);
            conversation = new ArrayList<>(result.conversationHistory());
            List<String> observations = observations(conversation);
            observations.forEach(observation -> log.info("[REACT] Observation received : {}", abbreviate(observation)));
            context.addStep(new ReActContext.Step(iteration, reasoning, actions, observations));

            applyEvaluation(context, evaluateGoal(context, userMedia, toolCatalog, null));
        }

        if (context.status() == ReActContext.GoalStatus.IN_PROGRESS) {
            context.updateStatus(ReActContext.GoalStatus.MAX_ITERATIONS_REACHED,
                    "limite de " + maxIterations + " iteration(s) atteinte");
            log.warn("[REACT] MAX_ITERATIONS atteint ({}) : arret controle", maxIterations);
            context.setFinalAnswer(controlledAnswer(context));
        } else if (context.finalAnswer() == null) {
            lastResponse = generateFinalAnswer(request, chain, conversation, options, context);
            context.setFinalAnswer(lastResponse.chatResponse().getResult().getOutput().getText());
        }

        log.info("[REACT] Final answer : statut {}, {} iteration(s), actions {}",
                context.status(), context.iteration(), context.allActions().stream().map(ReActAdvisor::toolName).toList());

        ChatResponse finalResponse = ChatResponse.builder()
                .from(lastResponse.chatResponse())
                .generations(List.of(new Generation(new AssistantMessage(context.finalAnswer()))))
                .build();
        return lastResponse.mutate().chatResponse(finalResponse).build();
    }

    private Goal defineGoal(String userQuery, List<Media> userMedia, String toolCatalog) {
        String text = "Outils disponibles :\n" + toolCatalog + "\n\nRequête utilisateur :\n" + userQuery
                + (userMedia.isEmpty() ? "" : "\n\n(Image jointe par l'utilisateur ci-dessus.)");
        Goal goal = controlClient.prompt()
                .options(OpenAiChatOptions.builder().temperature(0.0).build())
                .system(GOAL_INSTRUCTIONS)
                .user(u -> u.text(text).media(userMedia.toArray(Media[]::new)))
                .call()
                .entity(Goal.class);
        return goal != null ? goal
                : new Goal("Répondre à la demande : " + userQuery, "La réponse s'appuie sur les résultats des outils.");
    }

    private GoalEvaluation evaluateGoal(ReActContext context, List<Media> userMedia, String toolCatalog, String candidateAnswer) {
        String observations = context.steps().isEmpty() ? "(aucune observation)"
                : context.steps().stream()
                .map(step -> "Etape " + step.iteration() + " - actions : " + step.actions()
                        + "\nobservations : " + step.observations().stream()
                        .map(o -> o.length() > PROMPT_OBSERVATION_LIMIT ? o.substring(0, PROMPT_OBSERVATION_LIMIT) + "..." : o)
                        .toList())
                .collect(Collectors.joining("\n"));
        String input = "Outils disponibles :\n" + toolCatalog
                + "\nGoal : " + context.goal().description()
                + "\nCritère de réussite : " + context.goal().successCriteria()
                + "\nRequête et contenu fournis par l'utilisateur :\n" + context.userQuery()
                + (userMedia.isEmpty() ? "" : "\n(Image jointe par l'utilisateur ci-dessus.)")
                + "\nObservations :\n" + observations
                + (candidateAnswer != null ? "\nRéponse candidate du modèle (sans nouvel outil) :\n" + candidateAnswer : "");
        GoalEvaluation evaluation = controlClient.prompt()
                .options(OpenAiChatOptions.builder().temperature(0.0).build())
                .system(EVALUATION_INSTRUCTIONS)
                .user(u -> u.text(input).media(userMedia.toArray(Media[]::new)))
                .call()
                .entity(GoalEvaluation.class);
        return evaluation != null && evaluation.verdict() != null ? evaluation
                : new GoalEvaluation(GoalEvaluation.Verdict.IN_PROGRESS, "evaluation indisponible");
    }

    private static void applyEvaluation(ReActContext context, GoalEvaluation evaluation) {
        switch (evaluation.verdict()) {
            case ACHIEVED -> {
                context.updateStatus(ReActContext.GoalStatus.ACHIEVED, evaluation.justification());
                log.info("[REACT] Goal achieved : {}", evaluation.justification());
            }
            case UNAVAILABLE -> {
                context.updateStatus(ReActContext.GoalStatus.UNAVAILABLE, evaluation.justification());
                log.info("[REACT] Goal unavailable : {}", evaluation.justification());
            }
            case IN_PROGRESS -> log.info("[REACT] Goal in progress : {}", evaluation.justification());
        }
    }

    /** Reponse finale redigee par GPT-4o a partir du contexte accumule, sans nouvel appel d'outil. */
    private ChatClientResponse generateFinalAnswer(ChatClientRequest request, CallAdvisorChain chain,
                                                   List<Message> conversation, ChatOptions options, ReActContext context) {
        ChatOptions finalOptions = options.copy();
        if (finalOptions instanceof OpenAiChatOptions openAiOptions) {
            openAiOptions.setToolChoice("none");
        }
        List<Message> messages = new ArrayList<>(conversation);
        messages.add(new UserMessage(context.isGoalAchieved()
                ? "[REACT] Objectif atteint. Rédige maintenant la réponse finale à l'utilisateur, uniquement à partir des résultats des outils"
                + " et du contenu fourni par l'utilisateur ci-dessus, en respectant les règles."
                + " Si la réponse est un nombre d'éléments ou un total, énumère d'abord chaque élément concerné (une ligne par"
                + " élément, avec son identifiant ou sa valeur), puis donne le résultat calculé à partir de cette liste."
                : "[REACT] L'objectif ne peut pas être atteint : " + context.statusJustification()
                + " Donne d'abord les informations effectivement obtenues qui répondent à la demande, puis indique"
                + " clairement ce qui n'est pas disponible, uniquement à partir des résultats des outils et du contenu"
                + " fourni par l'utilisateur, sans rien inventer."));
        return chain.copy(this).nextCall(request.mutate().prompt(new Prompt(messages, finalOptions)).build());
    }

    /** Reponse deterministe lorsque la limite d'iterations est atteinte : aucune donnee non verifiee. */
    private String controlledAnswer(ReActContext context) {
        List<String> tools = context.allActions().stream().map(ReActAdvisor::toolName).toList();
        return "Je n'ai pas pu établir complètement l'objectif suivant dans la limite de " + maxIterations
                + " étape(s) : « " + context.goal().description() + " ». "
                + "Actions réalisées : " + (tools.isEmpty() ? "aucune" : String.join(", ", tools)) + ". "
                + "Pour éviter toute information non vérifiée, je ne fournis pas de réponse partielle. "
                + "Vous pouvez reformuler ou préciser votre demande.";
    }

    private static String reactInstructions(Goal goal) {
        return """
                # CYCLE REACT (contrôlé par le système)
                GOAL de cette requête : %s
                Critère de réussite : %s
                - Travaille par étapes : chaque étape est une action (appel d'un ou plusieurs outils) ;
                  le résultat t'est ensuite fourni comme observation.
                - OBLIGATOIRE : chaque message qui appelle un outil DOIT contenir, dans son texte, une
                  seule phrase courte commençant par "Justification :" qui indique pourquoi cette action
                  sert le GOAL. Ne détaille pas ton raisonnement interne.
                - Quand les observations suffisent à atteindre le GOAL, n'appelle plus d'outil.
                """.formatted(goal.description(), goal.successCriteria());
    }

    /** Catalogue des outils reellement enregistres (MCP + RAG), transmis au Goal et a l'evaluateur. */
    private static String toolCatalog(ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolOptions) || toolOptions.getToolCallbacks().isEmpty()) {
            return "(aucun outil)";
        }
        return toolOptions.getToolCallbacks().stream()
                .map(callback -> {
                    String description = callback.getToolDefinition().description().replaceAll("\\s+", " ");
                    return "- " + callback.getToolDefinition().name() + " : "
                            + (description.length() > 200 ? description.substring(0, 200) + "..." : description);
                })
                .collect(Collectors.joining("\n"));
    }

    private static List<Media> lastUserMedia(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage userMessage) {
                return userMessage.getMedia();
            }
        }
        return List.of();
    }

    private static String lastUserText(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage userMessage && userMessage.getText() != null) {
                return userMessage.getText();
            }
        }
        return "";
    }

    private static List<String> observations(List<Message> conversation) {
        if (!conversation.isEmpty() && conversation.get(conversation.size() - 1) instanceof ToolResponseMessage toolResponse) {
            return toolResponse.getResponses().stream()
                    .map(response -> response.name() + " -> " + response.responseData())
                    .toList();
        }
        return List.of();
    }

    private static String justification(String text, List<String> actions) {
        if (text == null || text.isBlank()) {
            // GPT-4o n'ecrit souvent aucun texte avec un appel d'outil : justification operationnelle minimale.
            return "Appel de " + actions.stream().map(ReActAdvisor::toolName).distinct().collect(Collectors.joining(", "))
                    + " pour obtenir l'information manquante au Goal (justification non redigee par le modele)";
        }
        String line = text.strip().lines().findFirst().orElse("").replaceFirst("(?i)^justification\\s*:\\s*", "");
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }

    private static String toolName(String action) {
        int parenthesis = action.indexOf('(');
        return parenthesis > 0 ? action.substring(0, parenthesis) : action;
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > LOG_LIMIT ? flat.substring(0, LOG_LIMIT) + "..." : flat;
    }
}
