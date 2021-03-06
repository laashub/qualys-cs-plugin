package com.qualys.plugins.containerSecurity;

import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.sql.Timestamp;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import hudson.AbortException;
import hudson.model.TaskListener;
import qshaded.com.google.gson.JsonElement;
import qshaded.com.google.gson.JsonObject;

import com.qualys.plugins.common.QualysAuth.QualysAuth;
import com.qualys.plugins.common.QualysClient.QualysCSClient;
import com.qualys.plugins.common.QualysClient.QualysCSResponse;
import com.qualys.plugins.containerSecurity.util.Helper;

public class GetImageVulnsCallable implements Callable<String> {
  
    private String imageId;
    private PrintStream buildLogger; 
    private int pollingIntervalForVulns;
    private int vulnsTimeout;
    public Set<String> reposArray;
    private String buildDirPath;
    private boolean isFailConditionsConfigured;
    private QualysAuth auth;
    
    private final static Logger logger = Logger.getLogger(GetImageVulnsCallable.class.getName());
    
    public GetImageVulnsCallable(String imageId, QualysCSClient qualysClient, TaskListener listener, 
    		int pollingIntervalForVulns, int vulnsTimeout, String buildDirPath, boolean isFailConditionsConfigured, QualysAuth auth) throws AbortException {
        this.imageId = imageId;
        this.buildLogger = listener.getLogger();
        this.pollingIntervalForVulns = pollingIntervalForVulns;
        this.vulnsTimeout = vulnsTimeout;
        this.buildDirPath = buildDirPath;
        this.isFailConditionsConfigured = isFailConditionsConfigured;
        this.auth = auth;
    }
    
    @Override
    public String call() throws QualysEvaluationException, Exception {
    	buildLogger.println("Thread for Image Id  = "+ imageId + ", " + Thread.currentThread().getName()+" Started");
    	try {
			return fetchScanResult();
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
    }

    public String fetchScanResult() throws QualysEvaluationException, Exception {
    	String scanResult = null;
    	long startTime = System.currentTimeMillis();
    	long vulnsTimeoutInMillis = TimeUnit.SECONDS.toMillis(vulnsTimeout);
    	long pollingInMillis = TimeUnit.SECONDS.toMillis(pollingIntervalForVulns);
    	//Keep checking if the scan results are available at polling intervals, until TIMEOUT_PERIOD is reached or results are available
    	try {
	    	while ((scanResult = getScanReport(imageId)) == null ) {
	    		long endTime = System.currentTimeMillis();
	    		if ((endTime - startTime) > vulnsTimeoutInMillis) {
	    			buildLogger.println("Failed to get scan result; timeout of " + vulnsTimeout + " seconds reached. Please check if image " + imageId + " is synced with API server.");
	    			if (isFailConditionsConfigured) {
	    				throw new QualysEvaluationException("Timeout reached."); 
	    			} else {
	    				break;
	    			}
	    		}
	    		try {
	    			buildLogger.println("Waiting for " + pollingIntervalForVulns + " seconds before making next attempt for " + imageId + " ...");
	    			Thread.sleep(pollingInMillis);
	    		} catch(InterruptedException e) {
	    			buildLogger.println("Error waiting for scan result..");
	    		}
	    	}
    	}
    	catch(Exception e) {
    		throw e;
    	} 
	    
    	if (!(scanResult == null || scanResult.isEmpty())) {
			Helper.createNewFile(buildDirPath, "qualys_" + imageId, scanResult, buildLogger);
    	} else {
    		if (isFailConditionsConfigured) {
    			throw new Exception("No vulnerabilities data for image " + imageId + " found.");
    		} else {
    			buildLogger.println("No vulnerabilities data for image " + imageId + " found.");
    			return null;
    		}
    	}
    	return scanResult;
    }

	private String getScanReport(String imageId) throws Exception {
	
	  	try {
    		buildLogger.println(new Timestamp(System.currentTimeMillis()) + " ["+ Thread.currentThread().getName() +"] - Calling API: "+ auth.getServer() + String.format(Helper.GET_SCAN_RESULT_API_PATH_FORMAT , imageId));
    	
    		QualysCSResponse resp = null;
            QualysCSClient qcs = new QualysCSClient(auth);
    	    resp = qcs.getImageDetails(imageId);
    	    logger.info("Received response code: " + resp.responseCode);
    	    
    	    //JP-210 -> continue polling for 5XX response code (common library returns 500 response with resp.errored=true)
    	    if(resp.responseCode >= 500 && resp.responseCode <= 599) {
    	    	buildLogger.println("HTTP Code: " + resp.responseCode + ". Image: N/A. Vulnerabilities: N/A.");
    	    	buildLogger.println("Waiting for image data from Qualys for image id " + imageId);
				return null;
			}
    	    
    	    if(resp.errored) {
    	    	logger.info("Qualys API server URL is not correct or it is not reachable. Error message: " + resp.errorMessage);
        		throw new Exception("Qualys API server URL is not correct or it is not reachable. Error message: " + resp.errorMessage);
    	    }
    	    
			buildLogger.println("Get scan result API for image " + imageId + " returned code : " + resp.responseCode + "; ");
			if(resp.responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
				buildLogger.println("Waiting for image data from Qualys for image id " + imageId);
				return null;
			}else if(resp.responseCode == HttpURLConnection.HTTP_OK && resp.response != null) {
				JsonObject jsonObj = resp.response;
				String scanResult = jsonObj.toString();
				JsonElement vulns = jsonObj.get("vulnerabilities");
				if (vulns == null || vulns.isJsonNull()) {
					buildLogger.println("Waiting for vulnerabilities data from Qualys for image id " + imageId);
					buildLogger.println("HTTP Code: 200. Image: known to Qualys. Vulnerabilities: To be processed.");
					return null;
				}
				return scanResult;
			} else {
				buildLogger.println("HTTP Code: "+ resp.responseCode +". Image: Not known to Qualys. Vulnerabilities: To be processed." +". API Response : " + resp.response);
				return null;
			}
    	}catch (Exception e) {
    		logger.info("Error fetching scan report for image "+ imageId +", reason : " + e.getMessage());
    		buildLogger.println("Error fetching scan report for image "+ imageId +", reason : " + e.getMessage());
    	} 
    	return null;
    }

    @Override
    public String toString(){
        return this.imageId;
    }
}

