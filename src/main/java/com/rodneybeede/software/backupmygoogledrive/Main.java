package com.rodneybeede.software.backupmygoogledrive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

class Main {
	private static final Logger log = Logger.getLogger(Main.class);
	

	// Google Client and Drive API specifics here
	private static final String APPLICATION_NAME = "Backup My Google Drive";
    
	
	public static void main(final String[] args) throws IOException, InterruptedException {
		if(null == args || args.length < 3 || args.length > 4) {
			System.err.println("Incorrect number of arguments");
			System.out.println("Usage:  java -jar " + Main.class.getProtectionDomain().getCodeSource().getLocation().getFile() + " <account username> <account oauth file> <destination directory> [user search filter]");
			System.out.println("https://developers.google.com/drive/v3/web/search-parameters#fn2");
			System.exit(255);
			return;
		}
		
		
		setupLogging();
				
		
		final String googleUsername = args[0];
		// Parse configuration options as canonical paths
		final Path oauthCredentialFile = Paths.get(args[1]).normalize().toAbsolutePath();
		final Path destinationDirectory = Paths.get(args[2]).normalize().toAbsolutePath();
		final String userQueryFilter = (args.length > 3) ? args[3] : null;
		
		log.info("Google Account username:  " + googleUsername);
		log.info("Google Account OAuth credential file:  " + oauthCredentialFile);
		log.info("Destination directory:  " + destinationDirectory);
		log.info("User Query Filter:  " + userQueryFilter);  // Might be null which is okay
		

		
		// Connect to Google Drive
		final GoogleDriveFacade google;
		try {
			google = new GoogleDriveFacade(oauthCredentialFile, Main.APPLICATION_NAME);
		} catch (final GeneralSecurityException e) {
			log.fatal("Unable to establish authenticated connection to Google", e);
			LogManager.shutdown();  //Forces log to flush
			System.exit(1);
			return;
		}

		
		// Get a listing of all Google files into a Map where key = fileid and value is the file
		final Map<String,com.google.api.services.drive.model.File> googleFileMap = google.getDriveFilesList(userQueryFilter);
		
		log.info("Number of Google Drive folders and files:  " + googleFileMap.size());
		
		
		// Download the files & folders
		log.info("Beginning download of files & folders.  Note:  If file has multiple parents (folders) it is only downloaded into the first parent");
		downloadGoogleFiles(google, googleFileMap, destinationDirectory);

        
        // Exit with appropriate status
		log.info("Backup has completed");
		

		LogManager.shutdown();  //Forces log to flush
		
		System.exit(0);  // All good
	}
	
	
	private static void setupLogging() {
		final Layout layout = new PatternLayout("%d{yyyy-MM-dd HH:mm:ss,SSS Z}\t%-5p\tThread=%t\t%c\t%m%n");
		
		
		Logger.getRootLogger().setLevel(Level.ALL);
				
		
		// Setup the logger to also log to the console
		final ConsoleAppender consoleAppender = new ConsoleAppender(layout);
		consoleAppender.setEncoding("UTF-8");
		consoleAppender.setThreshold(Level.INFO);
		Logger.getRootLogger().addAppender(consoleAppender);
		
		
		// Setup the logger to log into the current working directory
		final java.io.File logFile = new java.io.File(System.getProperty("user.dir"), "Backup_My_Google_Drive--" + getFormattedDatestamp(null) + ".log");
		final FileAppender fileAppender;
		try {
			fileAppender = new FileAppender(layout, logFile.getAbsolutePath());
		} catch (final IOException e) {
			e.printStackTrace();
			log.error(e,e);
			return;
		}
		fileAppender.setEncoding("UTF-8");
		fileAppender.setThreshold(Level.ALL);
		Logger.getRootLogger().addAppender(fileAppender);
		
		System.out.println("Logging to " + logFile.getAbsolutePath());
	}
	
	
	private static String getFormattedDatestamp(final Date date) {
		final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_Z");
		
		if(null == date) {
			return dateFormat.format(new Date());
		} else {
			return dateFormat.format(date);
		}
	}
	
	
	/**
	 * For each file if it has multiple parents (Google Drive Labels aka Folders) only the first parent will be used
	 * thus the file will only be downloaded once.
	 * 
	 * @param google
	 * @param googleFileMap
	 */
	private static void downloadGoogleFiles(final GoogleDriveFacade google, final Map<String,com.google.api.services.drive.model.File> googleFileMap, final Path destinationBaseDirectory) {
		// We are going to need to be able to identify the root file id, so grab it once to avoid excessive calls to Google which triggers their "User Rate Limit Exceeded" error
		final String rootFolderFileID;
		try {
			rootFolderFileID = google.getRootID();
		} catch (final IOException e) {
			log.fatal("Unable to determine Google Drive root folder FileID property!  Perhaps your connection to Google has failed or been blocked?", e);
			return;
		}
		
		for(final String id : googleFileMap.keySet()) {
			final com.google.api.services.drive.model.File driveFile = googleFileMap.get(id);
			
			log.trace(id + driveFile.getName());
			
			// We have to construct the local destination Path
			final List<String> fileParents = getParentNamesFromRootToImmediateParent(driveFile, googleFileMap, rootFolderFileID);
			log.trace(fileParents);
			
			
			Path dest = destinationBaseDirectory;
			for(final String ancestor : fileParents) {
				dest = FileUtilities.compatibleFilePath(dest, ancestor);
			}
			dest = FileUtilities.compatibleFilePath(dest, driveFile.getName());
			
	    	if(!dest.getFileName().toString().equals(driveFile.getName())) {
	    		log.warn("Had to change filename from " + driveFile.getName() + " to " + dest.getFileName() + " for file system compliance");
	    	}

	    	log.info("Downloading " + driveFile.getName());
	    	
	    	log.trace("Google filename  " + driveFile.getName() + "  with Google ID of  " + driveFile.getId());
	    	log.trace("Google filename  " + driveFile.getName() + "  with Mime Type of  " + driveFile.getMimeType());
	    	log.trace("Google filename  " + driveFile.getName() + "  with last modified of  " + driveFile.getModifiedTime());

	    	
	    	// If download had to do export for format conversion then new extension may have been added
	    	final Path actualLocalFile;
			try {
				actualLocalFile = google.downloadFile(driveFile, dest);
			} catch (final IOException e) {
				log.error("Error during download of " + driveFile.getName() + " to " + dest.toString(), e);
				continue;
			}
	    	
			try {
		    	log.info("Downloaded " + actualLocalFile + " of " + 
		    			Files.size(actualLocalFile) + " bytes and lastMod of " +
		    			Files.getLastModifiedTime(actualLocalFile)
		    			);
				
			} catch (final IOException e) {
				log.error("Cannot stat local file " + actualLocalFile.toString(), e);
			}
		}
	}
	
	
	/**
	 * Only looks at first parent each time.
	 * 
	 * @param driveFile
	 * @return List of parents in order of  <root>\ParentLvl1\parentLvl2\Parentlvl3\... but excludes <root> as it would not have a specific user-defined name
	 */
	private static List<String> getParentNamesFromRootToImmediateParent(final LinkedList<String> parentNames, com.google.api.services.drive.model.File driveFile, final Map<String,com.google.api.services.drive.model.File> googleFileMap, final String rootFolderFileId) {
		if(null != driveFile.getParents() && !driveFile.getParents().isEmpty()) {
			final String firstParentID = driveFile.getParents().iterator().next();  // First parent

			log.debug(driveFile.getName() + " has first parent ID of " + firstParentID);
			
			// https://developers.google.com/drive/v3/web/folder  "You can use the alias root to refer to the root folder anywhere a file ID is provided"
			if(rootFolderFileId.equals(firstParentID)) {
				return parentNames;
			}
			
			final com.google.api.services.drive.model.File firstParent = googleFileMap.get(firstParentID);
			if(null == firstParent) {
				log.error(firstParentID + " RETURNED NULL!, possibly because you own a file but it is stored inside a folder shared by someone else?");
				return parentNames;
			} else if(null == firstParent.getName()) {
				log.error(firstParent + " with ID of " + firstParentID + " returned NULL for getName().  Maybe a permissions issue?");
				return parentNames;
			}
			
			
			parentNames.addFirst(firstParent.getName());
			
			return getParentNamesFromRootToImmediateParent(parentNames, firstParent, googleFileMap, rootFolderFileId);
		} else {
			return parentNames;
		}
	}
	private static List<String> getParentNamesFromRootToImmediateParent(com.google.api.services.drive.model.File driveFile, final Map<String,com.google.api.services.drive.model.File> googleFileMap, final String rootFolderFileId) {
		return getParentNamesFromRootToImmediateParent(new LinkedList<String>(), driveFile, googleFileMap, rootFolderFileId);
	}
}
