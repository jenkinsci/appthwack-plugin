package org.jenkinsci.plugins.appthwack;

import java.util.ArrayList;
import java.util.Collection;

import hudson.model.Action;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Run;

import org.jenkinsci.plugins.appthwack.AppThwackTestResultAction;

/**
 * Contains collection of helper functions for common AppThwack/Jenkins actions.
 * @author hawker
 *
 */
public class AppThwackUtils {

    /**
     * Returns the AppThwack test run action from the most recent build.
     * @param project jenkins project which contains builds/runs to examine
     * @return
     */
    public static AppThwackTestResultAction previousAppThwackBuildAction(AbstractProject<?, ?> project) {
        AbstractBuild<?, ?> build = AppThwackUtils.previousAppThwackBuild(project);
        if (build == null) {
            return null;
        }
        return (AppThwackTestResultAction) build.getAction(AppThwackTestResultAction.class);
    }

    /**
     * Returns the most recent build which contained an AppThwack test run.
     * @param project jenkins project which contains runs to examine
     * @return
     */
    public static AbstractBuild<?, ?> previousAppThwackBuild(AbstractProject<?, ?> project) {
        AbstractBuild<?, ?> last = (AbstractBuild<?, ?>) project.getLastBuild();
        while (last != null) {
            if (last.getAction(AppThwackTestResultAction.class) != null) {
                break;
            }
            last = last.getPreviousBuild();
        }
        return last;
    }

    /**
     * Return collection of all previous builds of the given project which
     * contain an AppThwack test run.
     * @param project jenkins project which contains runs to examine
     * @return
     */
    public static ArrayList<AppThwackTestResultAction> previousAppThwackBuilds(AbstractProject<?, ?> project) {
        ArrayList<AppThwackTestResultAction> actions = new ArrayList<AppThwackTestResultAction>();
        
        AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) project.getLastBuild();
        while (build != null) {
            AppThwackTestResultAction action = build.getAction(AppThwackTestResultAction.class);
            if (action != null) {
                actions.add(action);
            }
            build = build.getPreviousBuild();
        }
        return actions;
    }

    /**
     * Returns the most recent AppThwack test result from the previous build
     * @param job job which generated an AppThwack test result
     * @return
     */
    public static AppThwackTestResult previousAppThwackBuildResult(Job job) {
        Run prev = job.getLastCompletedBuild();
        if (prev == null) {
            return null;
        }
        AppThwackTestResultAction action = (AppThwackTestResultAction) prev.getAction(AppThwackTestResultAction.class);
        if (action == null) {
            return null;
        }
        return action.getResult();
    }
}
