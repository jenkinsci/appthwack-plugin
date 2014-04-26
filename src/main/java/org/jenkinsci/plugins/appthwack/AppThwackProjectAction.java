package org.jenkinsci.plugins.appthwack;

import java.util.ArrayList;

import hudson.model.Action;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.util.ChartUtil;
import hudson.util.Graph;

import java.io.IOException;
import java.util.Calendar;

import javax.servlet.ServletException;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * AppThwack specific action tied to an Jenkins project.
 * 
 * This class is used for the top-level project view of your project
 * if it is configured to use AppThwack. It is responsible for serving up the project
 * level graph (for all AppThwack builds) as well as providing results for the most
 * recent AppThwack runs.
 * 
 * @author hawker
 *
 */
public class AppThwackProjectAction implements Action {

    private AbstractProject<?, ?> project;

    /**
     * Create new AppThwack project action
     * @param project Project which this action will be applied to
     */
    public AppThwackProjectAction(AbstractProject<?, ?> project) {
        this.project = project;
    }
    
    /**
     * Get project associated with this action
     * @return
     */
    public AbstractProject<?, ?> getProject() {
        return project;
    }

    /**
     * Returns true if there are any builds in the associated project.
     * @return 
     */
    public boolean shouldDisplayGraph() {
        return AppThwackUtils.previousAppThwackBuildAction(project) != null;
    }
    
    /**
     * Return the action of last build associated with AppThwack
     * @return most recent build with AppThwack or null
     */
    public AppThwackTestResultAction getLastBuildAction() {
        return AppThwackUtils.previousAppThwackBuildAction(project);
    }

    /**
     * Return the action of all previous builds associated with AppThwack
     * @return all AppThwack build actions for this project
     */
    public ArrayList<AppThwackTestResultAction> getLastBuildActions() {
        return AppThwackUtils.previousAppThwackBuilds(project);
    }

    /**
     * Return the actions of 'n' previous builds associated with AppThwack
     * @param n
     * @return
     */
    public ArrayList<AppThwackTestResultAction> getLastBuildActions(int n) {
        ArrayList<AppThwackTestResultAction> actions = getLastBuildActions();
        return new ArrayList<AppThwackTestResultAction>(actions.subList(0, Math.min(n, actions.size())));
    }

    /**
     * Serve up AppThwack project page which redirects to the latest
     * test results or 404.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doIndex(StaplerRequest request, StaplerResponse response) throws IOException {
        AbstractBuild<?, ?> prev = AppThwackUtils.previousAppThwackBuild(project);
        if (prev == null) {
            response.sendRedirect2("404");
        }
        else {
            // Redirect to build page of most recent AppThwack test run.
            response.sendRedirect2(String.format("../%d/%s", prev.getNumber(), getUrlName()));
        }
    }

    /**
     * Return trend graph of all AppThwack results for this project.
     * @param request
     * @param response
     * @throws IOException
     * @throws ServletException
     */
    public void doGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Get previous AppThwack build and results.
        AppThwackTestResultAction prev = getLastBuildAction();
        if (prev == null) {
            return;
        }
        AppThwackTestResult result = prev.getResult();
        if (result == null) {
            return;
        }

        // Create new graph for the AppThwack results of all runs in this project.
        Graph graph = AppThwackGraph.createResultTrendGraph(prev.getOwner(), false, result.getPreviousResults());
        graph.doPng(request, response);
    }

    public String getIconFileName() {
        return "/plugin/appthwack/thwack.png";
    }

    public String getDisplayName() {
        return "AppThwack";
    }

    public String getUrlName() {
        return "appthwack";
    }
}