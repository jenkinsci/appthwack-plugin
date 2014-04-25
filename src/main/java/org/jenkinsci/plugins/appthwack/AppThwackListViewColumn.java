package org.jenkinsci.plugins.appthwack;

import hudson.views.ListViewColumn;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Run;
import hudson.Extension;

import org.kohsuke.stapler.DataBoundConstructor;

import org.jenkinsci.plugins.appthwack.AppThwackUtils;

/**
 * AppThwack specific column entry to display (pass/warn/fail)
 * result numbers on the Jenkins homepage within the project list view
 * from the most recent run of that project.
 * @author hawker
 *
 */
public class AppThwackListViewColumn extends ListViewColumn {

    @DataBoundConstructor
    public AppThwackListViewColumn() {
        
    }

    /**
     * Returns true if the previous job has an AppThwack result with valid tests it should display.
     * @param job
     * @return
     */
    public boolean shouldDisplay(Job job) {
        AppThwackTestResult result = getPreviousResult(job);
        if (result == null || result.getTotalCount() <= 0) {
            return false;
        }
        return true;
    }

    /**
     * Get the AppThwack test run from the most recent build of this job
     * @param job
     * @return
     */
    public AppThwackTestResult getPreviousResult(Job job) {
        return AppThwackUtils.previousAppThwackBuildResult(job);
    }

    @Override
    public String getColumnCaption() {
        return getDescriptor().getDisplayName();
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ListViewColumn> {
        @Override
        public String getDisplayName() {
            return "AppThwack Pass/Warn/Fail";
        }
    }
}
