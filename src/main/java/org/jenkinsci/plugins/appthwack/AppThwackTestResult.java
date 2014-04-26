package org.jenkinsci.plugins.appthwack;

import java.util.List;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Collections;
import java.io.IOException;

import hudson.model.Action;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.tasks.junit.History;
import hudson.tasks.test.TestResult;
import hudson.tasks.test.TestObject;
import hudson.util.ChartUtil;
import hudson.util.Graph;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.appthwack.appthwack.*;

/**
 * Result object which contains high level result information, pass/warn/fail counters, and performance
 * stats for an AppThwack run.
 * 
 * A "snapshot" of the results of an AppThwack run at some point during its execution lifecycle.
 * New result objects will be created periodically during active runs and a final object will be stored
 * and attached to a Jenkins run, once it has completed.
 * 
 * @author hawker
 *
 */
public class AppThwackTestResult extends TestResult {

    private static final HashMap<String, Result> resultMap = new HashMap<String, Result>();
    private static final int DefaultTrendGraphSize = 3;

    private int id;
    private int passCount;
    private int warnCount;
    private int failCount;
    private int totalCount;
    private int errorCount;
    private String result;
    private String status;
    private int duration;
    private String url;
    private String reportFile;
    private String project;

    private String cpuAvg;
    private String memAvg;
    private String threadAvg;
    private String drawAvg;
    private String fpsAvg;

    private List<AppThwackResult.ResultContainer> passByDevice;
    private List<AppThwackResult.ResultContainer> warnByDevice;
    private List<AppThwackResult.ResultContainer> failByDevice;

    private AbstractBuild<?, ?> build;

    public AppThwackTestResult(AbstractBuild<?, ?> build, AppThwackRun run, AppThwackResult result) {
        this.build = build;
        if (result != null) {
            this.id = result.summary.id;
            this.status = result.summary.status;
            this.result = result.summary.result;
            this.duration = result.summary.minutesUsed;
            this.passCount = result.summary.passes;
            this.warnCount = result.summary.warnings;
            this.failCount = result.summary.failures;
            this.totalCount = result.summary.count;
            this.reportFile = result.summary.reportFile;
            this.passByDevice = result.passesByDevice;
            this.warnByDevice = result.warningsByDevice;
            this.failByDevice = result.failuresByDevice;
            if (result.performanceSummary != null) {
                if (result.performanceSummary.cpuAvg != null) {
                    this.cpuAvg = result.performanceSummary.cpuAvg.value;
                }
                if (result.performanceSummary.memoryAvg != null) {
                    this.memAvg = result.performanceSummary.memoryAvg.value;
                }
                if (result.performanceSummary.threadsAvg != null) {
                    this.threadAvg = result.performanceSummary.threadsAvg.value;
                }
                if (result.performanceSummary.drawAvg != null) {
                    this.drawAvg = result.performanceSummary.drawAvg.value;
                }
                if (result.performanceSummary.fpsAvg != null) {
                    this.fpsAvg = result.performanceSummary.fpsAvg.value;
                }
            }
        }
        if (run != null) {
            this.url = run.getWebUrl();
            this.project = run.getProject().name;
        }
    }

    static {
        resultMap.put("pass", Result.SUCCESS);
        resultMap.put("fail", Result.FAILURE);
        resultMap.put("warning", Result.UNSTABLE);
        resultMap.put("error", Result.FAILURE);
    }

    /**
     * Create the graph image for the number of pass/warn/fail results in a test run, for the previous three Jenkins runs.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Check the "If-Modified-Since" header and abort if we don't need re-create the graph.
        if (isCompleted()) {
            Calendar timestamp = getOwner().getTimestamp();
            if (request.checkIfModified(timestamp, response)) {
                return;
            }
        }

        // Create new graph for this AppThwack result.
        Graph graph = AppThwackGraph.createResultTrendGraph(build, isCompleted(), getPreviousResults(DefaultTrendGraphSize));
        graph.doPng(request, response);
    }

    /**
     * Create the graph image for the number of device minutes used in a test run, for the previous three Jenkins runs.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doDurationGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Check the "If-Modified-Since" header and abort if we don't need re-create the graph.
        if (isCompleted()) {
            Calendar timestamp = getOwner().getTimestamp();
            if (request.checkIfModified(timestamp, response)) {
                return;
            }
        }

        // Create new duration graph for this AppThwack result.
        Graph graph = AppThwackGraph.createDurationTrendGraph(build, isCompleted(), getPreviousResults(DefaultTrendGraphSize));
        graph.doPng(request, response);
    }

    /**
     * Create the graph image for the average CPU usage for all devices in a test run, for the previous three Jenkins runs.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doCpuGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Check the "If-Modified-Since" header and abort if we don't need re-create the graph.
        if (isCompleted()) {
            Calendar timestamp = getOwner().getTimestamp();
            if (request.checkIfModified(timestamp, response)) {
                return;
            }
        }

        // Create new performance graph for this AppThwack result.
        Graph graph = AppThwackGraph.createCpuTrendGraph(build, isCompleted(), getPreviousResults(DefaultTrendGraphSize));
        graph.doPng(request, response);
    }

    /**
     * Create the graph image for the average memory usage (KB) used for all devices in a test run, for the previous three Jenkins runs.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doMemoryGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Check the "If-Modified-Since" header and abort if we don't need re-create the graph.
        if (isCompleted()) {
            Calendar timestamp = getOwner().getTimestamp();
            if (request.checkIfModified(timestamp, response)) {
                return;
            }
        }

        // Create new performance graph for this AppThwack result.
        Graph graph = AppThwackGraph.createMemoryTrendGraph(build, isCompleted(), getPreviousResults(DefaultTrendGraphSize));
        graph.doPng(request, response);
    }

    /**
     * Create the graph image for the average number of threads used for all devices in a test run, for the previous three Jenkins runs.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doThreadGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Check the "If-Modified-Since" header and abort if we don't need re-create the graph.
        if (isCompleted()) {
            Calendar timestamp = getOwner().getTimestamp();
            if (request.checkIfModified(timestamp, response)) {
                return;
            }
        }

        // Create new performance graph for this AppThwack result.
        Graph graph = AppThwackGraph.createThreadTrendGraph(build, isCompleted(), getPreviousResults(DefaultTrendGraphSize));
        graph.doPng(request, response);
    }

    /**
     * Create the graph image for the average frame draw times for all devices in a test run, for the previous three Jenkins runs.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doFrameDrawGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Check the "If-Modified-Since" header and abort if we don't need re-create the graph.
        if (isCompleted()) {
            Calendar timestamp = getOwner().getTimestamp();
            if (request.checkIfModified(timestamp, response)) {
                return;
            }
        }

        // Create new performance graph for this AppThwack result.
        Graph graph = AppThwackGraph.createFrameDrawTrendGraph(build, isCompleted(), getPreviousResults(DefaultTrendGraphSize));
        graph.doPng(request, response);
    }

    /**
     * Create the graph image for the average frames per second of all devices in a test run, for the previous three Jenkins runs.
     * @param request
     * @param response
     * @throws IOException
     */
    public void doFpsGraph(StaplerRequest request, StaplerResponse response) throws IOException {
        // Abort if having Java AWT issues.
        if (ChartUtil.awtProblemCause != null) {
            response.sendRedirect2(String.format("%s/images/headless.png", request.getContextPath()));
            return;
        }

        // Check the "If-Modified-Since" header and abort if we don't need re-create the graph.
        if (isCompleted()) {
            Calendar timestamp = getOwner().getTimestamp();
            if (request.checkIfModified(timestamp, response)) {
                return;
            }
        }

        // Create new performance graph for this AppThwack result.
        Graph graph = AppThwackGraph.createFpsTrendGraph(build, isCompleted(), getPreviousResults(DefaultTrendGraphSize));
        graph.doPng(request, response);
    }

    /**
     * Return the AppThwack result of the most recent build which contained an AppThwack run.
     * @return
     */
    public AppThwackTestResult getPreviousResult() {
        AppThwackTestResultAction prev = AppThwackUtils.previousAppThwackBuildAction(build.getProject());
        if (prev == null) {
            return null;
        }
        return prev.getResult();
    }

    /**
     * Return a list of up to (n) of the most recent/previous AppThwack results.
     * @param n
     * @return
     */
    protected List<AppThwackTestResult> getPreviousResults(int n) {
        List<AppThwackTestResult> results = getPreviousResults();
        return results.subList(Math.max(0, results.size() - n), results.size());
        //return results.subList(0, Math.min(n, results.size()));
    }

    /**
     * Return a list of all AppThwack results from all builds previous to the build that this result is tied to.
     * The list is return in increasing, sequential order.
     * @return
     */
    protected List<AppThwackTestResult> getPreviousResults() {
        ArrayList<AppThwackTestResultAction> actions = AppThwackUtils.previousAppThwackBuilds(build.getProject());
        ArrayList<AppThwackTestResult> results = new ArrayList<AppThwackTestResult>();
        for (AppThwackTestResultAction action : actions) {
            AppThwackTestResult result = action.getResult();
            if (result == null) {
                continue;
            }
            results.add(result);
        }
        Collections.reverse(results);
        return results;
    }

    public float getCpuAvg() {
        try {
            return Float.parseFloat(cpuAvg);
        }
        catch (Exception e) {
            return 0;
        }
    }

    public float getMemoryAvg() {
        try {
            return Float.parseFloat(memAvg);
        }
        catch (Exception e) {
            return 0;
        }
    }

    public float getThreadAvg() {
        try {
            return Float.parseFloat(threadAvg);
        }
        catch (Exception e) {
            return 0;
        }
    }

    public float getDrawTimeAvg() {
        try {
            return Float.parseFloat(drawAvg);
        }
        catch (Exception e) {
            return 0;
        }
    }

    public float getFpsAvg() {
        try {
            return Float.parseFloat(fpsAvg);
        }
        catch (Exception e) {
            return 0;
        }
    }

    public String getReportUrl() {
        return url;
    }

    public int getRunId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getProject() {
        return project;
    }

    /**
     * Return true if this result is "completed". A completed result is a result
     * who is both marked as completed and has had its results archived for download.
     * @return
     */
    public Boolean isCompleted() {
        return status != null
                && status.equalsIgnoreCase("completed")
                && reportFile != null
                && !reportFile.isEmpty();
    }

    /**
     * Return a Jenkins build result which matches the result status from AppThwack.
     * @return
     */
    public Result getBuildResult() {
        return resultMap.get(result);
    }

    /**
     * Returns the AppThwack test result for the given id. The id will likely be the default
     * value generated by Jenkins, which is usually just the human readable name. Return this 
     * test result the ID's match, otherwise scan our previous runs looking for a matching result.
     * If no match is found, return null.
     * @param id
     * @return
     */
    public TestResult findCorrespondingResult(String id) {
        if (id == null || getId().equalsIgnoreCase(id)) {
            return this;
        }
        ArrayList<AppThwackTestResultAction> prevActions = AppThwackUtils.previousAppThwackBuilds(build.getProject());
        if (prevActions == null || prevActions.isEmpty()) {
            return null;
        }
        for (AppThwackTestResultAction action : prevActions) {
            AppThwackTestResult prevResult = action.getResult();
            if (prevResult == null) {
                continue;
            }
            if (prevResult.getId().equalsIgnoreCase(id)) {
                return prevResult;
            }
        }
        return null;
    }

    /**
     * Return list of test passes grouped by device.
     * @return
     */
    public List<AppThwackResult.ResultContainer> getPassByDevice() {
        return passByDevice;
    }

    /**
     * Return list of test warnings grouped by device.
     * @return
     */
    public List<AppThwackResult.ResultContainer> getWarnByDevice() {
        return warnByDevice;
    }

    /**
     * Return list of test failures group by device.
     * @return
     */
    public List<AppThwackResult.ResultContainer> getFailByDevice() {
        return failByDevice;
    }

    /**
     * Return number of test warnings in this result.
     * @return
     */
    public int getWarnCount() {
        return warnCount;
    }

    /**
     * Return number of test errors in this result.
     * @return
     */
    public int getErrorCount() {
        return errorCount;
    }

    /**
     * Return number of test failures in this result.
     * @return
     */
    @Override
    public int getFailCount() {
        return failCount;
    }

    /**
     * Return number of test passes in this result.
     * @return
     */
    @Override
    public int getPassCount() {
        return passCount;
    }

    /**
     * Return total number of tests run in this result.
     * @return
     */
    @Override
    public int getTotalCount() {
        return totalCount;
    }

    /**
     * Return total number of device minutes used by the run which generated this result.
     * @return
     */
    @Override
    public float getDuration() {
        return (float) duration;
    }

    /**
     * Return true if there are no tests for this result, false otherwise.
     * @return
     */
    public Boolean isEmpty() {
        return getTotalCount() == 0;
    }

    public TestObject getParent() {
        return null;
    }
    
    public AbstractBuild<?, ?> getOwner() {
        return build;
    }

    @Override
    public String getDisplayName() {
        return String.format("AppThwack #%s", id);
    }

    @Override
    public String getName() {
        return String.format("AppThwack%s", id);
    }

    @Override
    public String getSearchUrl() {
        return "appthwack";
    }
}
