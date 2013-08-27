package org.jenkinsci.plugins.appthwack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import net.sf.json.JSONObject;

import com.appthwack.appthwack.*;

/**
 * Post-build step for running tests on AppThwack.
 * @author ahawker
 *
 */
public class AppThwackRecorder extends Recorder {
	
	private PrintStream log;
	
	//private static final String DOMAIN = "https://appthwack.com";
	private static final String DOMAIN = "http://localhost:8001";
	
	private static final String JUNIT_TYPE = "junit";
	private static final String CALABASH_TYPE = "calabash";
	private static final String APP_EXPLORER_TYPE = "appexplorer";
	
	public String projectName;
	public String devicePoolName;
	public String appArtifact;
	public String type;
	public String calabashFeatures;
	public String calabashTags;
	public String testsArtifact;
	public String testsFilter;
	public String eventcount;
	public String username;
	public String password;
	public String launchdata;
	public String monkeyseed;

	@DataBoundConstructor
	public AppThwackRecorder(String projectName,
			String devicePoolName,
			String appArtifact,
			String type,
			String calabashFeatures,
			String calabashTags,
			String testsArtifact,
			String testsFilter,
			String eventcount,
			String username,
			String password,
			String launchdata,
			String monkeyseed) {
		this.projectName = projectName;
		this.devicePoolName = devicePoolName;
		this.appArtifact = appArtifact;
		this.type = type;
		this.calabashFeatures = calabashFeatures;
		this.calabashTags = calabashTags;
		this.testsArtifact = testsArtifact;
		this.testsFilter = testsFilter;
		this.eventcount = eventcount;
		this.username = username;
		this.password = password;
		this.launchdata = launchdata;
		this.monkeyseed = monkeyseed;
	}
	
	public String getApiKey() {
		return getDescriptor().apiKey;
	}
	
	@Override
	public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
		//Build failed earlier in the chain, no need to test.
		if (build.getResult().isWorseOrEqualTo(Result.FAILURE)) {
			return false;
		}
		//Something is f'd.
		EnvVars env = getEnvironment(build, listener);
		if (env == null) {
			return false;
		}
		log = listener.getLogger();
		
		//Artifacts location for this build on master.
		FilePath artifactsDir = new FilePath(build.getArtifactsDir());
		
		//Workspace (potentially remote if using slave).
		FilePath workspace = build.getWorkspace();
		
		//Validate user selection & input values.
		boolean isValid = validateConfiguration() && validateTestConfiguration();
		if (!isValid) {
			LOG("Invalid configuration.");
			return false;
		}
		
		//Create & configure the AppThwackApi client.
		String apiKey = getApiKey();
		AppThwackApi api = new AppThwackApi(apiKey, DOMAIN);
		
		//Get AppThwack project from user provided name.
		LOG(String.format("Using Project '%s'", projectName));
		AppThwackProject project = api.getProject(projectName);
		if (project == null) {
			LOG(String.format("Project '%s' not found.", projectName));
			return false;
		}
		
		//Get AppThwack device pool from user provided name.
		LOG(String.format("Using DevicePool '%s'", devicePoolName));
		AppThwackDevicePool devicePool = project.getDevicePool(devicePoolName);
		if (devicePool == null) {
			LOG(String.format("DevicePool '%s' not found.", devicePoolName));
			return false;
		}

		//Create/Validate app artifact and make local copy.
		File appArtifactFile = getArtifactFile(artifactsDir, workspace, env.expand(appArtifact));
		if (appArtifactFile == null || !appArtifactFile.exists()) {
			LOG("Application Artifact not found.");
			return false;
		}
		
		//Upload app.
		LOG(String.format("Using App '%s'", appArtifactFile.getAbsolutePath()));
		AppThwackFile app = uploadFile(api, appArtifactFile);
		if (app == null) {
			LOG(String.format("Failed to upload app '%s'", appArtifactFile.getAbsolutePath()));
			return false;
		}
		
		//Upload test content.
		AppThwackFile tests = uploadTestContent(api, env, artifactsDir, workspace);
		if (tests == null && !type.equalsIgnoreCase(APP_EXPLORER_TYPE)) {
			LOG("Failed to upload required test content.");
			return false;
		}
		
		//Create test run name.
		String name = String.format("%s (Jenkins)", appArtifactFile.getName());
		
		//Schedule the test run.
		LOG(String.format("Scheduling '%s' run '%s'", type, name));
		AppThwackRun run = scheduleTestRun(project, devicePool, type, name, app, tests);
		
		//Huzzah!
		LOG(String.format("Congrats! See your test run at %s/%s", DOMAIN, run.toString()));
		return true;
	}
	
	/**
	 * Gets a local File instance of a glob file pattern, pulling it from a slave if necessary.
	 * @param artifactsDir artifacts directory on master 
	 * @param workspace workspace to search for matches, usually the jenkins workspace
	 * @param pattern Glob pattern to find artifacts
	 * @return
	 */
	public File getArtifactFile(FilePath artifactsDir, FilePath workspace, String pattern) {
		try {
			//Find glob matches.
			FilePath[] matches = workspace.list(pattern);
			if (matches == null || matches.length == 0) {
				LOG(String.format("No Artifacts found using pattern '%s'", pattern));
				return null;
			}
			//Use the first match if multiple found.
			FilePath artifact = matches[0];
			if (matches.length > 1) {
				LOG(String.format("WARNING: Multiple artifact matches found, defaulting to '%s'", artifact.getName()));
			}
			LOG(String.format("Archiving artifact '%s'", artifact.getName()));
			
			//Copy file (master or slave) to the build artifact directory on the master.
			FilePath localArtifact = new FilePath(artifactsDir, artifact.getName());
			artifact.copyTo(localArtifact);
			return new File(localArtifact.toString());
		}
		catch (Exception e) {
			LOG(String.format("Unable to find artifact %s", e.toString()));
			return null;
		}
	}
	
	/**
	 * Schedules a test run on AppThwack.
     * @param project user project which will contain the run
     * @param pool device pool to run tests on
     * @param type type of tests to run
     * @param name name of test run
     * @param app object returned from uploading user app
     * @param tests object returned from uploading user test content
	 * @return object which represents a remote run on AppThwack
	 */
	private AppThwackRun scheduleTestRun(AppThwackProject project,
										AppThwackDevicePool pool,
										String type,
										String name,
										AppThwackFile app,
										AppThwackFile tests) {
		try {
			if (type.equalsIgnoreCase(JUNIT_TYPE)) {
				return project.scheduleJUnitRun(app, tests, name, pool, testsFilter);
			}
			if (type.equalsIgnoreCase(CALABASH_TYPE)) {
				return project.scheduleCalabashRun(app, tests, name, pool, calabashTags);
			}
			if (type.equalsIgnoreCase(APP_EXPLORER_TYPE)) {
				HashMap<String, String> explorerOptions = new HashMap<String, String>()
						{{
							put("eventcount", eventcount);
							put("username", username);
							put("password", password);
							put("launchdata", launchdata);
							put("monkeyseed", monkeyseed);
						}};
				return project.scheduleAppExplorerRun(app, name, pool, explorerOptions);
			}
			return null;
		}
		catch (AppThwackException e) {
			LOG(String.format("Failed to schedule test run '%s' of type '%s'", name, type));
			return null;
		}
	}
	
	/**
	 * Uploads newly built app to AppThwack or returns null on error.
	 * @param api AppThwackApi instance to use.
     * @param apk File object of the app to upload.
     * @return Object representing a remote file stored on AppThwack
	 */
	private AppThwackFile uploadFile(AppThwackApi api, File file) {
		try {
			return api.uploadFile(file);
		}
		catch (AppThwackException e) {
			LOG(String.format("Exception '%s' raised when uploading file '%s'", e.getMessage(), file.getAbsolutePath()));
			return null;
		}
	}
	
	/**
	 * Upload JUnit/Robotium test app or Calabash scripts to AppThwack.
	 * @param api AppThwackApi instance to use.
	 * @param env Environment variables for the current job.
	 * @param artifactsDir artifacts path on master for this build
	 * @param workspace path to local/remote workspace for this build
	 * @return object which represents a remote file on AppThwack.
	 */
	private AppThwackFile uploadTestContent(AppThwackApi api, EnvVars env, FilePath artifactsDir, FilePath workspace) {
		//JUnit/Robotium: Upload tests .apk file.
		if (type.equalsIgnoreCase(JUNIT_TYPE)) {
			//Get JUnit/Robotium apk from given glob pattern.
			File tests = getArtifactFile(artifactsDir, workspace, env.expand(testsArtifact));
			if (tests == null) {
				LOG("No tests provided for JUnit/Robotium selection.");
				return null;
			}
			LOG(String.format("Using JUnit/Robotium content '%s'", tests.getAbsolutePath()));
			
			//Upload this test apk to AppThwack.
			AppThwackFile upload = uploadFile(api, tests);
			if (upload == null) {
				LOG(String.format("Failed to upload tests '%s'", tests.getAbsolutePath()));
				return null;
			}
			return upload;
		}
		//Calabash: Upload features.zip file.
		if (type.equalsIgnoreCase(CALABASH_TYPE)) {
			if (!calabashFeatures.endsWith(".zip")) {
				LOG("Calabash content must be of type .zip");
				return null;
			}
			
			//Get Calabash features.zip from given glob pattern.
			File features = getArtifactFile(artifactsDir, workspace, env.expand(calabashFeatures));
			if (!features.exists()) {
				LOG(String.format("Calabash content not found at '%s'", features.getAbsolutePath()));
				return null;
			}
			LOG(String.format("Using Calabash content '%s'", features.getAbsolutePath()));
			
			//Upload the features.zip to AppThwack.
			AppThwackFile upload = uploadFile(api, features);
			if (upload == null) {
				LOG(String.format("Failed to upload tests '%s'", features.getAbsolutePath()));
				return null;
			}
			return upload;
		}
		return null;
	}
	
	/**
	 * Validate top level configuration values.
	 * @return
	 */
	private boolean validateConfiguration() {
		//[Required]: API Key
		String apiKey = getApiKey();
		if (apiKey == null || apiKey.isEmpty()) {
			LOG("API Key must be set.");
			return false;
		}
		//[Required]: Project
		if (projectName == null || projectName.isEmpty()) {
			LOG("Project must be set.");
			return false;
		}
		//[Required]: DevicePool
		if (devicePoolName == null || devicePoolName.isEmpty()) {
			LOG("DevicePool must be set.");
			return false;
		}
		//[Required]: App Artifact
		if (appArtifact == null || appArtifact.isEmpty()) {
			LOG("Application Artifact must be set.");
			return false;
		}
		//[Required]: Type (Radio Block)
		if (type == null || type.isEmpty()) {
			LOG("Test type must be set.");
			return false;
		}
		return true;
	}
	
	/**
	 * Validate user selected test type and additional configuration values.
	 * @return
	 */
	private boolean validateTestConfiguration() {
		//JUnit/Robotium
		if (type.equalsIgnoreCase(JUNIT_TYPE)) {
			//[Required]: Tests Artifact
			if (testsArtifact == null || testsArtifact.isEmpty()) {
				LOG("Tests Artifact must be set.");
				return false;
			}
			return true;
		}
		//Calabash
		else if (type.equalsIgnoreCase(CALABASH_TYPE)) {
			//[Required]: Features Path
			if (calabashFeatures == null || calabashFeatures.isEmpty()) {
				LOG("Calabash Features must be set.");
				return false;
			}
			//[Optional]: Calabash Tags
			if (!(calabashTags == null || calabashTags.isEmpty())) {
	        	String[] tags = calabashTags.split(",");
	        	for (String tag : tags) {
	        		if (!tag.startsWith("@")) {
	        			LOG(String.format("Missing '@' in tag '%s'.", tag));
	        			return false;
	        		}
	        	}
			}
			return true;
		}
		//AppExplorer
		else if (type.equalsIgnoreCase(APP_EXPLORER_TYPE)) {
			//[Optional]: EventCount (int)
			if (eventcount != null && !eventcount.isEmpty()) {
				if (!isNumeric(eventcount)) {
					LOG("EventCount must be a number.");
					return false;
				}
			}
			//[Optional]: MonkeySeed (int)
			if (monkeyseed != null && !monkeyseed.isEmpty()) {
				if (!isNumeric(monkeyseed)) {
					LOG("MonkeySeed must be a number.");
					return false;
				}
			}
			return true;
		}
		//Unknown
		LOG(String.format("Invalid test type %s", type));
		return false;
	}
	
	/**
	 * Helper method to eat exceptions when capturing the environment as the perform function isn't checked.
	 * @param build AbstractBuild instance for the current Jenkins job.
	 * @param listener BuildListener instance for the current Jenkins job.
	 * @return Environment for the current Jenkins job.
	 */
	private EnvVars getEnvironment(AbstractBuild build, BuildListener listener) {
		try {
			return build.getEnvironment(listener);
		}
		catch (IOException e) {
			return null;
		}
		catch (InterruptedException e) {
			return null;
		}
	}
	
	/**
	 * Helper method to validate that a given value is a number. This is dumb. :/
	 * @param value
	 * @return
	 */
	private boolean isNumeric(String value) {
		try {
			Integer.parseInt(value);
			return true;
		}
		catch (NumberFormatException e) {
			return false;
		}
	}
	
	/**
	 * Helper method for writing entries to the Jenkins log.
	 * @param msg
	 */
	private void LOG(String msg) {
		log.println(String.format("[AppThwack] %s", msg));
	}
	
	/**
	 * Helper method for the jelly view to determine test type.
	 * @param type
	 * @return
	 */
	public String isType(String type) {
		return (this.type.equalsIgnoreCase(type)) ? "true" : "";
	}
	
    @Override
    public DescriptorImpl getDescriptor() {	
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * In a concurrent environment, this MUST run after the has completed.
     * @return
     */
	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}
	
	/**
	 * Descriptor for AppThwackRecorder.
	 * @author ahawker
	 *
	 */
    @Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    	
    	public String apiKey;
    	
    	public DescriptorImpl() {
    		load();
    	}
    	
        /**
         * Validate the user account API key.
         * @param apiKey
         * @return
         */
        public FormValidation doCheckApiKey(@QueryParameter String apiKey) {
        	if (apiKey == null || apiKey.isEmpty()) {
        		return FormValidation.error("Required!");
        	}
        	return FormValidation.ok();
        }
        
    	/**
    	 * Validate the user selected project.
    	 * @param project
    	 * @return
    	 */
        public FormValidation doCheckProjectName(@QueryParameter String projectName) {
        	if (projectName == null || projectName.isEmpty()) {
        		return FormValidation.error("Required!");
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user selected device pool.
         * @param pool
         * @return
         */
        public FormValidation doCheckDevicePoolName(@QueryParameter String devicePoolName) {
        	if (devicePoolName == null || devicePoolName.isEmpty()) {
        		return FormValidation.error("Required!");
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered file path to local Calabash features.
         * @param calabashContent
         * @return
         */
        public FormValidation doCheckCalabashFeatures(@QueryParameter String calabashFeatures) {
        	if (calabashFeatures == null || calabashFeatures.isEmpty()) {
        		return FormValidation.error("Required!");
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user selected type dropdown. Sanity!
         * @param type
         * @return
         */
        public FormValidation doCheckType(@QueryParameter String type) {
        	boolean isKnownType = Arrays.asList(JUNIT_TYPE, CALABASH_TYPE, APP_EXPLORER_TYPE).contains(type);
        	if (!isKnownType) {
        		return FormValidation.error(String.format("Unknown test type %s.", type));
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered artifact for JUnit/Robotium test content.
         * @param testsArtifact
         * @return
         */
        public FormValidation doCheckTestsArtifact(@QueryParameter String testsArtifact) {
        	if (testsArtifact == null || testsArtifact.isEmpty()) {
        		return FormValidation.error("Required!");
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered artifact for the application.
         * @param appArtifact
         * @return
         */
        public FormValidation doCheckAppArtifact(@QueryParameter String appArtifact) {
        	if (appArtifact == null || appArtifact.isEmpty()) {
        		return FormValidation.error("Required!");
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered Calabash tags.
         * @param calabashTags
         * @return
         */
        public FormValidation doCheckCalabashTags(@QueryParameter String calabashTags) {
        	if (calabashTags == null || calabashTags.isEmpty()) {
        		return FormValidation.ok();
        	}
        	String[] tags = calabashTags.split(",");
        	for (String tag : tags) {
        		if (!tag.startsWith("@")) {
        			return FormValidation.error(String.format("Missing '@' in tag '%s'.", tag));
        		}
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered JUnit/Robotium filter.
         * @param testsFilter
         * @return
         */
        public FormValidation doCheckTestsFilter(@QueryParameter String testsFilter) {
        	return FormValidation.ok();
        }
        
        /**
         * Bind descriptor object to capture global plugin settings from 'Manage Jenkins'.
         * @param req
         * @param json
         * @return
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject json) {
        	req.bindJSON(this, json);
        	save();
        	return true;
        }
		
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}
		
		@Override
		public String getDisplayName() {
			return "Run Tests on AppThwack";
		}
	}
}
