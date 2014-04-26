package org.jenkinsci.plugins.appthwack;

import java.util.List;
import java.util.ArrayList;

import hudson.Functions;
import hudson.model.Action;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.util.Graph;
import hudson.util.Area;
import hudson.util.ShiftedCategoryAxis;
import hudson.util.ChartUtil.NumberOnlyBuildLabel;
import hudson.util.DataSetBuilder;

import java.awt.Color;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.CategoryItemRenderer;
import org.jfree.chart.title.LegendTitle;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.ui.RectangleEdge;
import org.jfree.ui.RectangleInsets;

import org.jenkinsci.plugins.appthwack.AppThwackTestResult;

/**
 * Generate stylized graphs for AppThwack results.
 * @author hawker
 *
 */
public class AppThwackGraph extends Graph {

    public static final Color PassColor = new Color(0x0c9b49);
    public static final Color WarnColor = new Color(0xea562f);
    public static final Color FailColor = new Color(0xbe2326);
    public static final Color DurationColor = new Color(0x083250);
    public static final Color FrameColor = new Color(0xEBEBDC);
    
    private final String xLabel;
    private final String yLabel;
    private final CategoryDataset dataset;
    private Color[] colors;

    public AppThwackGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, Area size, CategoryDataset dataset, String xLabel, String yLabel, Color...colors) {
        // Toggle the graph timestamp so we don't cache the graph image if the run isn't completed.
        super(((isCompleted) ? owner.getTimestamp().getTimeInMillis() : -1), size.width, size.height);
        this.dataset = dataset;
        this.xLabel = xLabel;
        this.yLabel = yLabel;
        this.colors = colors;
    }
 
    /**
     * Create graph based on the given dataset and constraints.
     * @return
     */
    protected JFreeChart createGraph() {
        // Create chart.
        JFreeChart chart = ChartFactory.createStackedAreaChart(null, null, yLabel, dataset, PlotOrientation.VERTICAL, true, true, false);
        chart.setBackgroundPaint(Color.WHITE);

        // Create chart legend.
        LegendTitle legend = chart.getLegend();
        legend.setPosition(RectangleEdge.RIGHT);

        // Create chart plot.
        CategoryPlot plot  = (CategoryPlot) chart.getPlot();
        plot.setForegroundAlpha(0.7f);
        plot.setBackgroundPaint(Color.WHITE);
        plot.setRangeGridlinePaint(Color.darkGray);

        // Create domain (x) axis.
        CategoryAxis domain = new ShiftedCategoryAxis(xLabel);
        domain.setCategoryLabelPositions(CategoryLabelPositions.UP_45);
        domain.setLowerMargin(0.0);
        domain.setUpperMargin(0.0);
        domain.setCategoryMargin(0.0);
        plot.setDomainAxis(domain);

        // Create range (y) axis.
        NumberAxis range = (NumberAxis) plot.getRangeAxis();
        range.setAutoRange(true);
        range.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        // Create renderer and paint the chart.
        CategoryItemRenderer renderer = plot.getRenderer();
        plot.setInsets(new RectangleInsets(5.0, 0, 0, 5.0));

        // Set chart colors for sections.
        for (int i=0; i<colors.length; i++) {
            renderer.setSeriesPaint(i, colors[i]);
        }
        return chart;
    }

    /**
     * Generate a results (pass/warn/fail) trend graph for recent results.
     * @param owner build which owns the latest result
     * @param isCompleted flag to denote if the result is completed which determines our caching
     * @param results list of previous to latest results which generate the trend
     * @return
     */
    public static Graph createResultTrendGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, List<AppThwackTestResult> results) {
        List<String> rows = new ArrayList<String>();
        List<Number> vals = new ArrayList<Number>();
        List<NumberOnlyBuildLabel> cols = new ArrayList<NumberOnlyBuildLabel>();

        for (AppThwackTestResult result : results) {
            AbstractBuild<?, ?> build = result.getOwner();
    
            // Create label for this result using its Jenkins build number.
            NumberOnlyBuildLabel label = new NumberOnlyBuildLabel(build);

            // Add 'pass' results
            rows.add("Pass");
            cols.add(label);
            vals.add(result.getPassCount());

            // Add 'warn' results
            rows.add("Warn");
            cols.add(label);
            vals.add(result.getWarnCount());
    
            // Add 'fail' results.
            rows.add("Fail");
            cols.add(label);
            vals.add(result.getFailCount());
        }

        CategoryDataset dataset = createDataset(vals, rows, cols);
        Color[] colors = new Color[] { AppThwackGraph.PassColor, AppThwackGraph.WarnColor, AppThwackGraph.FailColor };
        return new AppThwackGraph(owner, isCompleted, getGraphSize(), dataset, "Build #", "# of tests", colors);
    }

    /**
     * Generate a duration trend graph for device minutes used for recent results.
     * @param owner build which owns the latest result
     * @param isCompleted flag to denote if the result is completed which determines our caching
     * @param results list of previous to latest results which generate the trend
     * @return
     */
    public static Graph createDurationTrendGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, List<AppThwackTestResult> results) {
        DataSetBuilder<String, NumberOnlyBuildLabel> builder = new DataSetBuilder<String, NumberOnlyBuildLabel>();

        for (AppThwackTestResult result : results) {
            // Create label for this result using its Jenkins build number.
            AbstractBuild<?, ?> build = result.getOwner();
            NumberOnlyBuildLabel label = new NumberOnlyBuildLabel(build);

            // Attach duration value for all results in our trend.
            builder.add(result.getDuration(), "Minutes", label);
        }

        CategoryDataset dataset = builder.build();
        Color[] colors = new Color[] { AppThwackGraph.DurationColor };
        return new AppThwackGraph(owner, isCompleted, getGraphSize(), dataset, "Build #", "Device Minutes Used", colors);
    }

    /**
     * Generate a CPU usage trend graph for recent results.
     * @param owner build which owns the latest result
     * @param isCompleted flag to denote if the result is completed which determines our caching
     * @param results list of previous to latest results which generate the trend
     * @return
     */
    public static Graph createCpuTrendGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, List<AppThwackTestResult> results) {
        DataSetBuilder<String, NumberOnlyBuildLabel> builder = new DataSetBuilder<String, NumberOnlyBuildLabel>();

        for (AppThwackTestResult result : results) {
            // Create label for this result using its Jenkins build number.
            AbstractBuild<?, ?> build = result.getOwner();
            NumberOnlyBuildLabel label = new NumberOnlyBuildLabel(build);

            // Attach CPU average value for each result in our trend.
            float cpuAvg = result.getCpuAvg();
            builder.add(cpuAvg, "CPU", label);
        }

        CategoryDataset dataset = builder.build();
        Color[] colors = new Color[] { AppThwackGraph.PassColor };
        return new AppThwackGraph(owner, isCompleted, getGraphSize(), dataset, "Build #", "CPU Average (%)", colors);
    }

    /**
     * Generate a memory usage (KB) trend graph for recent results.
     * @param owner build which owns the latest result
     * @param isCompleted flag to denote if the result is completed which determines our caching
     * @param results list of previous to latest results which generate the trend
     * @return
     */
    public static Graph createMemoryTrendGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, List<AppThwackTestResult> results) {
        DataSetBuilder<String, NumberOnlyBuildLabel> builder = new DataSetBuilder<String, NumberOnlyBuildLabel>();

        for (AppThwackTestResult result : results) {
            // Create label for this result using its Jenkins build number.
            AbstractBuild<?, ?> build = result.getOwner();
            NumberOnlyBuildLabel label = new NumberOnlyBuildLabel(build);

            // Attach memory average value for each result in our trend.
            float memoryAvg = result.getMemoryAvg();
            builder.add(memoryAvg, "Memory", label);
        }

        CategoryDataset dataset = builder.build();
        Color[] colors = new Color[] { AppThwackGraph.PassColor };
        return new AppThwackGraph(owner, isCompleted, getGraphSize(), dataset, "Build #", "Memory Average (KB)", colors);
    }

    /**
     * Generate a thread count trend graph for recent results.
     * @param owner build which owns the latest result
     * @param isCompleted flag to denote if the result is completed which determines our caching
     * @param results list of previous to latest results which generate the trend
     * @return
     */
    public static Graph createThreadTrendGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, List<AppThwackTestResult> results) {
        DataSetBuilder<String, NumberOnlyBuildLabel> builder = new DataSetBuilder<String, NumberOnlyBuildLabel>();

        for (AppThwackTestResult result : results) {
            // Create label for this result using its Jenkins build number.
            AbstractBuild<?, ?> build = result.getOwner();
            NumberOnlyBuildLabel label = new NumberOnlyBuildLabel(build);

            // Attach thread count average value for each result in our trend.
            float threadAvg = result.getThreadAvg();
            builder.add(threadAvg, "Threads", label);
        }

        CategoryDataset dataset = builder.build();
        Color[] colors = new Color[] { AppThwackGraph.PassColor };
        return new AppThwackGraph(owner, isCompleted, getGraphSize(), dataset, "Build #", "Threads Average", colors);
    }

    /**
     * Generate a frame draw time trend graph for recent results.
     * @param owner build which owns the latest result
     * @param isCompleted flag to denote if the result is completed which determines our caching
     * @param results list of previous to latest results which generate the trend
     * @return
     */
    public static Graph createFrameDrawTrendGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, List<AppThwackTestResult> results) {
        DataSetBuilder<String, NumberOnlyBuildLabel> builder = new DataSetBuilder<String, NumberOnlyBuildLabel>();

        for (AppThwackTestResult result : results) {
            // Create label for this result using its Jenkins build number.
            AbstractBuild<?, ?> build = result.getOwner();
            NumberOnlyBuildLabel label = new NumberOnlyBuildLabel(build);

            // Attach Frame Draw Times average value for each result in our trend.
            float drawTime = result.getDrawTimeAvg();
            builder.add(drawTime, "Frame Draw Time", label);
        }

        CategoryDataset dataset = builder.build();
        Color[] colors = new Color[] { AppThwackGraph.PassColor };
        return new AppThwackGraph(owner, isCompleted, getGraphSize(), dataset, "Build #", "Frame Draw Time Average (ms)", colors);
    }

    /**
     * Generate a FPS trend graph for recent results.
     * @param owner build which owns the latest result
     * @param isCompleted flag to denote if the result is completed which determines our caching
     * @param results list of previous to latest results which generate the trend
     * @return
     */
    public static Graph createFpsTrendGraph(AbstractBuild<?, ?> owner, Boolean isCompleted, List<AppThwackTestResult> results) {
        DataSetBuilder<String, NumberOnlyBuildLabel> builder = new DataSetBuilder<String, NumberOnlyBuildLabel>();

        for (AppThwackTestResult result : results) {
            // Create label for this result using its Jenkins build number.
            AbstractBuild<?, ?> build = result.getOwner();
            NumberOnlyBuildLabel label = new NumberOnlyBuildLabel(build);

            // Attach FPS average value for each result in our trend.
            float fps = result.getFpsAvg();
            builder.add(fps, "FPS", label);
        }

        CategoryDataset dataset = builder.build();
        Color[] colors = new Color[] { AppThwackGraph.PassColor };
        return new AppThwackGraph(owner, isCompleted, getGraphSize(), dataset, "Build #", "FPS Average", colors);
    }

    private static CategoryDataset createDataset(List<Number> values, List<String> rows, List<NumberOnlyBuildLabel> columns) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        for (int i=0; i<values.size(); i++) {
            dataset.addValue(values.get(i), rows.get(i), columns.get(i));
        }
        return dataset;
    }

    private static Area getGraphSize() {
        Area resolution = Functions.getScreenResolution();
        if (resolution == null || resolution.width <= 800) { //too small or unknown
            return new Area(250, 100);
        }
        return new Area(500, 200);
    }
}