package net.youssfi.enstbot.agents;

/**
 * Goal explicite de l'agent (bloc "Goal" de la partie Agentic AI) : objectif operationnel
 * propre a UNE requete utilisateur, distinct du system prompt general.
 *
 * @param description     l'objectif, formule comme une action a accomplir
 * @param successCriteria les informations qui doivent etre obtenues pour considerer l'objectif atteint
 */
public record Goal(String description, String successCriteria) {
}
