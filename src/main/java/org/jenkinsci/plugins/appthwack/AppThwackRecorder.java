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
	
	private static final String DOMAIN = "https://appthwack.com";
	
	private static final String JUNIT_TYPE = "junit";
	private static final String CALABASH_TYPE = "calabash";
	private static final String MONKEYTALK_TYPE = "monkeytalk";
	private static final String KIF_TYPE = "kif";
	private static final String UIA_TYPE = "uia";
	private static final String BUILTIN_ANDROID_TYPE = "builtinAndroid";
	private static final String BUILTIN_IOS_TYPE = "builtinIOS";
	
	public String projectName;
	public String devicePoolName;
	public String appArtifact;
	public String type;
	public String calabashFeatures;
	public String calabashTags;
	public String junitArtifact;
	public String junitFilter;
	public String monkeyArtifact;
	public String uiaArtifact;
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
			String junitArtifact,
			String junitFilter,
			String monkeyArtifact,
			String uiaArtifact,
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
		this.junitArtifact = junitArtifact;
		this.junitFilter = junitFilter;
		this.monkeyArtifact = monkeyArtifact;
		this.uiaArtifact = uiaArtifact;
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
	public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		//Build failed earlier in the chain, no need to test.
		if (build.getResult().isWorseOrEqualTo(Result.FAILURE)) {
			return false;
		}
		EnvVars env =  build.getEnvironment(listener);
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
		if (tests == null && requiresTestContent(type)) {
			LOG(String.format("Failed to upload required '%s' test content.", type));
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
			//JUnit/Robotium Tests
			if (type.equalsIgnoreCase(JUNIT_TYPE)) {
				return project.scheduleJUnitRun(app, tests, name, pool, junitFilter);
			}
			//Calabash (Android/iOS) Tests
			if (type.equalsIgnoreCase(CALABASH_TYPE)) {
				return project.scheduleCalabashRun(app, tests, name, pool, calabashTags);
			}
			//Built-in Android (AppExplorer + ExerciserMonkey)
			if (type.equalsIgnoreCase(BUILTIN_ANDROID_TYPE)) {
				HashMap<String, String> explorerOptions = new HashMap<String, String>();
				if (eventcount != null && !eventcount.isEmpty() && isNumeric(eventcount)) {
					explorerOptions.put("eventcount", eventcount);
				}
				if (username != null && !username.isEmpty()) {
					explorerOptions.put("username", username);
				}
				if (password != null && !password.isEmpty()) {
					explorerOptions.put("password", password);
				}
				if (launchdata != null && !launchdata.isEmpty()) {
					explorerOptions.put("launchdata", launchdata);
				}
				if (monkeyseed != null && !monkeyseed.isEmpty() && isNumeric(monkeyseed)) {
					explorerOptions.put("monkeyseed", monkeyseed);
				}
				return project.scheduleAppExplorerRun(app, name, pool, explorerOptions);
			}
			//MonkeyTalk (Android)
			if (type.equalsIgnoreCase(MONKEYTALK_TYPE)) {
				return project.scheduleMonkeyTalkRun(app, tests, name, pool);
			}
			//KIF (iOS)
			if (type.equalsIgnoreCase(KIF_TYPE)) {
				return project.scheduleKIFRun(app, name, pool);
			}
			//UIA (iOS)
			if (type.equalsIgnoreCase(UIA_TYPE)) {
				return project.scheduleUIARun(app, tests, name, pool);
			}
			//Build-in iOS (Monkey)
			if (type.equalsIgnoreCase(BUILTIN_IOS_TYPE)) {
				return project.scheduleBuiltinIOSRun(app, name, pool);
			}
			//Unknown!
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
		File tests = null;
		
		//JUnit/Robotium: Upload tests .apk file.
		if (type.equalsIgnoreCase(JUNIT_TYPE)) {
			//Get JUnit/Robotium apk from given glob pattern.
			tests = getArtifactFile(artifactsDir, workspace, env.expand(junitArtifact));
		}
		//Calabash: Upload features.zip file.
		else if (type.equalsIgnoreCase(CALABASH_TYPE)) {
			//Get Calabash features.zip from given glob pattern.
			tests = getArtifactFile(artifactsDir, workspace, env.expand(calabashFeatures));
		}
		else if (type.equalsIgnoreCase(MONKEYTALK_TYPE)) {
			//Get MonkeyTalk tests (.zip) from given pattern.
			tests = getArtifactFile(artifactsDir, workspace, env.expand(monkeyArtifact));
		}
		//UIA: Upload tests .js file.
		else if (type.equalsIgnoreCase(UIA_TYPE)) {
			//Get UIA .js file from given glob pattern.
			tests = getArtifactFile(artifactsDir, workspace, env.expand(uiaArtifact));
		}
		
		//Test type has no explicit test artifacts or failed to find them.
		if (tests == null) {
			return null;
		}
		//Provided valid path but no file exists there.
		if (!tests.exists()) {
			LOG(String.format("No test content found at '%s'", tests.getAbsolutePath()));
			return null;
		}
		
		LOG(String.format("Using '%s' test content from '%s'", type, tests.getAbsolutePath()));
		
		//Upload test artifacts to AppThwack.
		AppThwackFile upload = uploadFile(api, tests);
		if (upload == null) {
			LOG(String.format("Failed to upload test content '%s'", tests.getAbsolutePath()));
			return null;
		}
		return upload;
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
			if (junitArtifact == null || junitArtifact.isEmpty()) {
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
			//[Required]: Features.zip
			if (!calabashFeatures.endsWith(".zip")) {
				LOG("Calabash content must be of type .zip");
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
		//Android Built-in 
		else if (type.equalsIgnoreCase(BUILTIN_ANDROID_TYPE)) {
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
		//MonkeyTalk
		else if (type.equalsIgnoreCase(MONKEYTALK_TYPE)) {
			if (monkeyArtifact == null || monkeyArtifact.isEmpty()) {
				LOG("MonkeyTalk Artifact must be set.");
				return false;
			}
			return true;
		}
		//UIA
		else if (type.equalsIgnoreCase(UIA_TYPE)) {
			//[Required]: Tests Artifact
			if (uiaArtifact == null || uiaArtifact.isEmpty()) {
				LOG("UIA tests artifact is empty.");
				return false;
			}
			return true;
		}
		//KIF
		else if (type.equalsIgnoreCase(KIF_TYPE)) {
			//KIF has no configuration options.
			return true;
		}
		//iOS Built-in
		else if (type.equalsIgnoreCase(BUILTIN_IOS_TYPE)) {
			//iOS Built-in has no configuration options.
			return true;
		}
		//Unknown
		LOG(String.format("Invalid test type %s", type));
		return false;
	}
	
	/**
	 * Helper method which returns True if the given test type requires explicit test content file(s).
	 * @return
	 */
	private boolean requiresTestContent(String type) {
		return !(type.equalsIgnoreCase(BUILTIN_ANDROID_TYPE)
				|| type.equalsIgnoreCase(KIF_TYPE)
				|| type.equalsIgnoreCase(BUILTIN_IOS_TYPE));
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
        	boolean isKnownType = Arrays.asList(
        			JUNIT_TYPE, 
        			CALABASH_TYPE, 
        			BUILTIN_ANDROID_TYPE,
        			KIF_TYPE,
        			UIA_TYPE,
        			BUILTIN_IOS_TYPE).contains(type);
        	
        	if (!isKnownType) {
        		return FormValidation.error(String.format("Unknown test type %s.", type));
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered artifact for JUnit/Robotium test content.
         * @param junitArtifact
         * @return
         */
        public FormValidation doCheckJunitArtifact(@QueryParameter String junitArtifact) {
        	if (junitArtifact == null || junitArtifact.isEmpty()) {
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
         * @param junitFilter
         * @return
         */
        public FormValidation doCheckJunitFilter(@QueryParameter String junitFilter) {
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered UIA artifact.
         * @param uiaArtifact
         * @return
         */
        public FormValidation doCheckUiaArtifact(@QueryParameter String uiaArtifact) {
        	if (uiaArtifact == null || uiaArtifact.isEmpty()) {
        		return FormValidation.error("Required!");
        	}
        	return FormValidation.ok();
        }
        
        /**
         * Validate the user entered MonkeyTalk artifact.
         * @param monkeyArtifact
         * @return
         */
        public FormValidation doCheckMonkeyArtifact(@QueryParameter String monkeyArtifact) {
        	if (monkeyArtifact == null || monkeyArtifact.isEmpty()) {
        		return FormValidation.error("Required");
        	}
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
