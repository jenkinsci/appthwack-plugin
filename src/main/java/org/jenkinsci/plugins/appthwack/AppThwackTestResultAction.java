package org.jenkinsci.plugins.appthwack;

import java.io.PrintStream;

import hudson.FilePath;
import hudson.model.Action;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.DirectoryBrowserSupport;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TestResult;
import hudson.util.ChartUtil;
import hudson.util.Graph;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.lang.InterruptedException;

import javax.servlet.ServletException;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.export.Exported;
import org.jenkinsci.plugins.appthwack.AppThwackTestResult;

import com.appthwack.appthwack.AppThwackRun;

/**
 * Action which controls the execution management and results updating for AppThwack runs.
 * 
 * This object is analogous to an AppThwack run.
 * 
 * @author hawker
 *
 */
public class AppThwackTestResultAction extends AbstractTestResultAction<AppThwackTestResultAction> implements StaplerProxy {

    private static final int DefaultUpdateInterval = 30 * 1000;

    private PrintStream log;

    private AppThwackTestResult result;

    public AppThwackTestResultAction(AbstractBuild<?, ?> owner, PrintStream log) {
        super(owner);
        this.log = log;
    }

    /**
     * Returns the Jenkins result which matches the result of this AppThwack run.
     * @return
     */
    public Result getBuildResult() {
        return getResult().getBuildResult();
    }

    /**
     * Blocking function which periodically polls the given AppThwack run until its completed. During this waiting period,
     * we will grab the latest results reported by AppThwack and updated our internal result "snapshot" which will be used
     * to populate/inform the UI of test results/progress.
     * @param run
     */
    public void waitForRunCompletion(AppThwackRun run) {
        while (true) {
            result = new AppThwackTestResult(owner, run, run.getResults());
            if (result.isCompleted()) {
                break;
            }
            try {
                Thread.sleep(DefaultUpdateInterval);
            }
            catch(InterruptedException ex) {
                break;
            }
        }
    }

    /**
     * Returns the most recent AppThwack test action from the previous build.
     * @return
     */
    @Override
    public AppThwackTestResultAction getPreviousResult() {
        AbstractBuild<?, ?> build = getOwner();
        if (owner == null) {
            return null;
        }
        return AppThwackUtils.previousAppThwackBuildAction(build.getProject());
    }

    /**
     * Returns a snapshot of the current results for this AppThwack run.
     * @return
     */
    @Override
    public AppThwackTestResult getResult() {
        return result;
    }

    /**
     * Returns a snapshot of the current results for this AppThwack run.
     * @return
     */
    @Override
    public AppThwackTestResult getTarget() {
        return getResult();
    }

    /**
     * Returns the number of failed tests for this AppThwack run.
     * @return
     */
    @Override
    public int getFailCount() {
        return getResult().getFailCount();
    }

    /**
     * Returns the total number of tests for this AppThwack run.
     * @return
     */
    @Override
    public int getTotalCount() {
        return getResult().getTotalCount();
    }

    public AbstractBuild<?, ?> getOwner() {
        return owner;
    }

    @Override
    public String getUrlName() {
        return "appthwack";
    }

    @Override
    public String getDisplayName() {
        return "AppThwack";
    }

    @Override
    public String getIconFileName() {
        return "/plugin/appthwack/thwack.png";
    }
}