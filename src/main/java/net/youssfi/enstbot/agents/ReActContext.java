package net.youssfi.enstbot.agents;

import java.util.ArrayList;
import java.util.List;

/**
 * Etat d'un cycle ReAct pour une requete : Goal, etapes Reasoning / Action / Observation,
 * etat de l'objectif et reponse finale.
 */
public class ReActContext {

    public enum GoalStatus { IN_PROGRESS, ACHIEVED, UNAVAILABLE, MAX_ITERATIONS_REACHED }

    /**
     * Une iteration du cycle.
     *
     * @param reasoning    courte justification operationnelle de l'action (jamais un raisonnement detaille)
     * @param actions      outils appeles, avec leurs arguments
     * @param observations resultats renvoyes par ces outils
     */
    public record Step(int iteration, String reasoning, List<String> actions, List<String> observations) {
    }

    private final Goal goal;
    private final String userQuery;
    private final List<Step> steps = new ArrayList<>();
    private int iteration;
    private GoalStatus status = GoalStatus.IN_PROGRESS;
    private String statusJustification;
    private String finalAnswer;

    public ReActContext(Goal goal, String userQuery) {
        this.goal = goal;
        this.userQuery = userQuery;
    }

    public int nextIteration() {
        return ++iteration;
    }

    public void addStep(Step step) {
        steps.add(step);
    }

    public void updateStatus(GoalStatus status, String justification) {
        this.status = status;
        this.statusJustification = justification;
    }

    public boolean isGoalAchieved() {
        return status == GoalStatus.ACHIEVED;
    }

    public List<String> allActions() {
        return steps.stream().flatMap(step -> step.actions().stream()).toList();
    }

    public Goal goal() { return goal; }
    public String userQuery() { return userQuery; }
    public int iteration() { return iteration; }
    public List<Step> steps() { return steps; }
    public GoalStatus status() { return status; }
    public String statusJustification() { return statusJustification; }
    public String finalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }
}
