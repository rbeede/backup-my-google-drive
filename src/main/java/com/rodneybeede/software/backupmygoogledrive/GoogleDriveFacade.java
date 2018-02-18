package com.rodneybeede.software.backupmygoogledrive;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow.Builder;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.common.base.Strings;

public class GoogleDriveFacade {
	private final FileDataStoreFactory DATA_STORE_FACTORY;
	private static final Collection<String> AUTH_SCOPES = (Collection<String>) Collections.unmodifiableCollection(
			Arrays.asList(DriveScopes.DRIVE_METADATA_READONLY, DriveScopes.DRIVE_READONLY)
			);
	private final String APPLICATION_NAME;

	private final HttpTransport HTTP_TRANSPORT;
	private final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	
	private Drive cachedGoogleDrive;  // getDriveService()
	

	public GoogleDriveFacade(final Path oauthCredentialLocation, final String googleAppName)
			throws GeneralSecurityException, IOException {
		// Known bug in FileDataStoreFactory causes spurious Warning message on
		// STDOUT
		// https://github.com/google/google-http-java-client/issues/315
		final java.util.logging.Logger buggyLogger = java.util.logging.Logger
				.getLogger(FileDataStoreFactory.class.getName());
		buggyLogger.setLevel(java.util.logging.Level.SEVERE);  // Still gives some useful error messages

		this.DATA_STORE_FACTORY = new FileDataStoreFactory(oauthCredentialLocation.toFile());
		this.APPLICATION_NAME = googleAppName;

		this.HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
	}
	

	/**
	 * If current legit OAuth token is stored on disk then it is used to get a Credential from Google.  Otherwise opens
	 * default system web browser if possible.  If browser cannot be opened shows prompt in text console and
	 * asks user to manually obtain OAuth token
	 * 
	 * @return Valid credential connected to provided Google Account
	 * @throws IOException If unable to obtain credential
	 */
	private Credential getGoogleCredential() throws IOException {
		// Load client secrets (which really aren't secret since this is a desktop application)
		final GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(this.JSON_FACTORY,
				new InputStreamReader(this.getClass().getResourceAsStream("/client_secret.json")));

		final Builder builder = new Builder(this.HTTP_TRANSPORT, this.JSON_FACTORY, clientSecrets, GoogleDriveFacade.AUTH_SCOPES);
		builder.setDataStoreFactory(this.DATA_STORE_FACTORY);
		builder.setAccessType("offline");

		final GoogleAuthorizationCodeFlow flow = builder.build();
		final AuthorizationCodeInstalledApp authApp = new AuthorizationCodeInstalledApp(flow,
				new LocalServerReceiver());

		// If user has previous authorized token just validates and uses that token with no user interaction
		// If no existing token or current token is invalid will cause prompt at this stage
		return authApp.authorize("user");
	}
	

	/**
	 * Thread safe.  May return cached build so it only happens once.
	 * 
	 * @return
	 * @throws IOException
	 */
	public synchronized Drive getDriveService() throws IOException {
		if(null != this.cachedGoogleDrive)  return this.cachedGoogleDrive;
		//  Beware if you optimize later with a double checked locking idiom
		
        final Credential credential = getGoogleCredential();
        
        final Drive.Builder driveBuilder = new Drive.Builder(this.HTTP_TRANSPORT, this.JSON_FACTORY, credential);
        driveBuilder.setApplicationName(this.APPLICATION_NAME);
        
        this.cachedGoogleDrive = driveBuilder.build();
        
        return this.cachedGoogleDrive;
    }
	

	/**
	 * Always uses filter that excludes items in the trash and does an AND userQueryFilter to that.
	 * 
	 * <p>
	 * Only grabs metadata for files of the following:
	 * </p>
	 * <ul>
	 * <li>id</li>
	 * <li>name</li>
	 * <li>modifiedTime (last modification date)</li>
	 * </ul>
	 * 
	 * @param userQueryFilter Optional query that is logical AND to the query
	 * @return Map of file id to Google File object
	 * @throws IOException 
	 */
	public Map<String,com.google.api.services.drive.model.File> getDriveFilesList(final String userQueryFilter) throws IOException {
		final Map<String,com.google.api.services.drive.model.File> fileIdToFileMap = new HashMap<>();
		
		// Add the user's query filter if provided to our base query.
		final String driveQueryString = "trashed=false" +
			// If specified add the user query otherwise don't
			(!Strings.isNullOrEmpty(userQueryFilter) ?  (" and " + userQueryFilter)  : "");

		// Google API returns results in pages so we need to go through all pages and store all results
		for(String nextPageToken = ""; null != nextPageToken; ) {
			final FileList fileListQueryResult = this.getDriveService().files().list()
				.setQ(driveQueryString)
				.setSpaces("drive")
				// setFields is important otherwise you don't get back required metadata needed to download later
				.setFields("nextPageToken, files(id, name, mimeType, modifiedTime, parents)")
				.setPageToken(nextPageToken)  // if null or empty string means look at page 1
				.setPageSize(1000)  // Maximum allowed as documented in API for files.list is 1000 as of 3/7/2016 & APIv3
				.execute();
			
			for(final File file : fileListQueryResult.getFiles()) {
				fileIdToFileMap.put(file.getId(), file);
			}
			
			// If null indicates no more pages to query/gather
			nextPageToken = fileListQueryResult.getNextPageToken();
		}

		return fileIdToFileMap;
	}
	
	
	/**
	 * Dependent on the MIME type it may be necessary to export (convert) the file from Google native file to a format
	 * that can be downloaded (e.g. xlsx, docx, etc.)
	 * 
	 * <p>
	 * If conversion is necessary the appropriate extension is appended to destination which is why a return value of
	 * Path is given since it may have been necessary to change the destination name.
	 * </p>
	 * 
	 * <p>
	 * The last modified time of destination is set to the same as the Google Drive File
	 * </p>
	 * 
	 * @param file The Google File to download (must have pre-populated name, id, last modified fields)
	 * @param destination Desired location to create file (full pathname including desired filename).  If conversion needed then file extension will be added for you.
	 * @return Actual destination Path where file was downloaded
	 * @throws IOException If during write of content an IO error occurs
	 * @throws InvalidPathException If destination could not be used as a valid Path (typically file system restriction
	 * on characters, reserved name, or length)
	 */
	public Path downloadFile(final com.google.api.services.drive.model.File file, final Path destination) throws IOException {
    	switch(file.getMimeType()) {  // https://developers.google.com/drive/v3/web/mime-types   
    		// https://developers.google.com/drive/v3/web/manage-downloads
			case "application/vnd.google-apps.folder":
				return createLocalDirectory(file, destination);
    		case "application/vnd.google-apps.document":
    			return downloadFileWithConversion(file,
    					FileUtilities.compatibleFilePath(destination.getParent(),
    					destination.getFileName() + ".docx"),
    					"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    		case "application/vnd.google-apps.spreadsheet":
    			return downloadFileWithConversion(file,
    					FileUtilities.compatibleFilePath(destination.getParent(),
    					destination.getFileName() + ".xlsx"), 
    					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    		case "application/vnd.google-apps.presentation":
    			return downloadFileWithConversion(file,
    					FileUtilities.compatibleFilePath(destination.getParent(),
    					destination.getFileName() + ".pptx"),
    					"application/vnd.openxmlformats-officedocument.presentationml.presentation");
    		case "application/vnd.google-apps.drawing":
    			return downloadFileWithConversion(file,
    					FileUtilities.compatibleFilePath(destination.getParent(),
    					destination.getFileName() + ".svg"),
    					"image/svg+xml");
    		case "application/vnd.google-apps.script":
    			return downloadFileWithConversion(file,
    					FileUtilities.compatibleFilePath(destination.getParent(),
    					destination.getFileName() + ".json"),
    					"application/vnd.google-apps.script+json");

    		default:
    			// Not a Google native type file so download straight as-is
				return downloadFileNoConversion(file, destination);
    	}
	}
	
	
	private Path createLocalDirectory(final File file, final Path destination) throws IOException {
		Files.createDirectories(destination);
		
		Files.setLastModifiedTime(destination, FileTime.fromMillis(file.getModifiedTime().getValue()));
		
		return destination;
	}
	
	
	private Path downloadFileWithConversion(final com.google.api.services.drive.model.File file, final Path destination, final String conversionType) throws IOException {
		if(!Files.exists(destination.getParent())) {
			// Not atomic but that is okay since an attempt to double create (thread sync possibility) is okay
			Files.createDirectories(destination.getParent());
		}
		
		final OutputStream ostream = Files.newOutputStream(destination);
		
		this.getDriveService().files().export(file.getId(), conversionType).executeAndDownloadTo(ostream);
		
		ostream.close();
		
		destination.toFile().setWritable(false, false);
		
		// Set local mod time to match Google Drive's
		Files.setLastModifiedTime(destination, FileTime.fromMillis(file.getModifiedTime().getValue()));

		return destination;
	}
	
	
	private Path downloadFileNoConversion(final com.google.api.services.drive.model.File file, final Path destination) throws IOException {
		if(!Files.exists(destination.getParent())) {
			// Not atomic but that is okay since an attempt to double create (thread sync possibility) is okay
			Files.createDirectories(destination.getParent());
		}

		
		final OutputStream ostream = Files.newOutputStream(destination);
		
		this.getDriveService().files().get(file.getId()).executeMediaAndDownloadTo(ostream);
		
		ostream.close();
		
		destination.toFile().setWritable(false, false);
		
		// Set local mod time to match Google Drive's
		Files.setLastModifiedTime(destination, FileTime.fromMillis(file.getModifiedTime().getValue()));

		return destination;
	}
	
	
	public String getRootID() throws IOException {
		// As per  https://developers.google.com/drive/v3/web/migration
		// files.get with fileId=root and ?fields=id
		
		final File root = this.getDriveService().files().get("root").execute();
		
		return root.getId();  // Not going to be "root"
	}
}
