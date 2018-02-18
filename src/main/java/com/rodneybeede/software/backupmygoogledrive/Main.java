package com.rodneybeede.software.backupmygoogledrive;

import java.io.File;
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
		if(null == args || args.length < 3 || args.length > 5) {
			System.err.println("Incorrect number of arguments");
			System.out.println("Usage:\tjava -jar " + Main.class.getProtectionDomain().getCodeSource().getLocation().getFile() + " <account username> <account oauth file> <destination directory> [--google-api-filter=search parameters] [--post-parentid-tree-exclude=id]");
			System.out.println("Usage:\tjava -jar " + Main.class.getProtectionDomain().getCodeSource().getLocation().getFile() + " <account username> <account oauth file> --tree-listing [--google-api-filter=search parameters] [--post-parentid-tree-exclude=id]");
			System.out.println("\t\t" + "--post-parentid-tree-exclude=id  means if any file has a parent, grandparent, etc. with matching id then exclude it.  Useful for filtering out things like 'My Computer' and all files/folders underneath.  Must be processed after API call query due to API limits.  If possible use --google-api-filter");
			System.out.println();
			System.out.println("https://developers.google.com/drive/v3/web/search-parameters#fn2");
			System.exit(255);
			return;
		}
		
		
		setupLogging();
				
		
		final String googleUsername = args[0];
		// Parse configuration options as canonical paths
		final Path oauthCredentialFile = Paths.get(args[1]).normalize().toAbsolutePath();
		final Path destinationDirectory;
		if("--tree-listing".equals(args[2])) {
			destinationDirectory = null;
		} else {
			destinationDirectory = Paths.get(args[2]).normalize().toAbsolutePath();			
		}
		final String googleApiFilter;
		final String treeExcludeId;
		
		{  // scoping
			String apiArg = null;
			String treeArg = null;
			
			for(int i = 3; i < args.length; i++) {
				if(args[i].startsWith("--google-api-filter=")) {
					apiArg = args[i].substring("--google-api-filter=".length());
				} else if(args[i].startsWith("--post-parentid-tree-exclude=")) {
					treeArg = args[i].substring("--post-parentid-tree-exclude=".length());
				} else {
					// Illegal argument
					log.error("Illegal argument:  " + args[i]);
					System.exit(255);
					return;
				}
			}
			
			googleApiFilter = apiArg;  // May still be null
			treeExcludeId = treeArg;   // May still be null
		}
		
		log.info("Google Account username:  " + googleUsername);
		log.info("Google Account OAuth credential file:  " + oauthCredentialFile);
		if(null == destinationDirectory) {
			log.info("Tree Listing option given");
		} else {
			log.info("Destination directory:  " + destinationDirectory);			
		}
		log.info("Google API Filter:  " + googleApiFilter);  // Might be null which is okay
		log.info("Tree Exclude ID:  " + treeExcludeId);  // Might be null which is okay
		

		
		// Connect to Google Drive
		final GoogleDriveFacade google;
		try {
			google = new GoogleDriveFacade(oauthCredentialFile, Main.APPLICATION_NAME);
			log.info("Authentication to Google was successful");
		} catch (final GeneralSecurityException e) {
			log.fatal("Unable to establish authenticated connection to Google", e);
			LogManager.shutdown();  //Forces log to flush
			System.exit(1);
			return;
		}

		
		// Get a listing of all Google files into a Map where key = fileid and value is the file
		// At this point the map does not have the <root>\grandparent\parent\file mappings yet
		final Map<String,com.google.api.services.drive.model.File> googleFileMap = google.getDriveFilesList(googleApiFilter);
		
		log.info("Number of Google Drive folders and files (before tree exclude id):  " + googleFileMap.size());
		
		
		// =========================================
		if(null == destinationDirectory) {
			// Tree Listing option was given
			log.info("Beginning tree listing with given filters (if any)...");
		} else {
			// Download the files & folders
			log.info("Beginning download of files & folders.  Note:  If file has multiple parents (folders) it is only downloaded into the first parent");
		}
		
		downloadGoogleFiles(google, googleFileMap, destinationDirectory, treeExcludeId);  // null for destinationDirectory will signal to skip actual download

        
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
	 * @param destinationBaseDirectory If null signals to not actually download file only output where it would have gone
	 */
	private static void downloadGoogleFiles(final GoogleDriveFacade google, final Map<String,com.google.api.services.drive.model.File> googleFileMap, final Path destinationBaseDirectory, final String treeExcludeID) {
		// We are going to need to be able to identify the root file id, so grab it once to avoid excessive calls to Google which triggers their "User Rate Limit Exceeded" error
		final String rootFolderFileID;
		try {
			rootFolderFileID = google.getRootID();
		} catch (final IOException e) {
			log.fatal("Unable to determine Google Drive root folder FileID property!  Perhaps your connection to Google has failed or been blocked?", e);
			return;
		}
		
		if(null == destinationBaseDirectory) {
			// Tree Listing headers
			System.out.print("id");
			System.out.print('\t');
			System.out.print("name");
			System.out.print('\t');
			System.out.print("parents");
			System.out.print('\t');
			System.out.print("mimetype");
			System.out.print('\t');
			System.out.print("modifiedtime");
			System.out.println();
		}
		
		for(final String id : googleFileMap.keySet()) {
			final com.google.api.services.drive.model.File driveFile = googleFileMap.get(id);
			
			log.trace(id + "\t" + driveFile.getName());
			
			// We have to construct the local destination Path
			final List<String> fileParents = getParentNamesFromRootToImmediateParent(driveFile, googleFileMap, rootFolderFileID, treeExcludeID);
			log.trace(fileParents);

			
	    	log.trace("Google filename  " + driveFile.getName() + "  with Google ID of  " + driveFile.getId());
	    	log.trace("Google filename  " + driveFile.getName() + "  with Mime Type of  " + driveFile.getMimeType());
	    	log.trace("Google filename  " + driveFile.getName() + "  with last modified of  " + driveFile.getModifiedTime());
	    	
	    	
	    	if(null == fileParents) {
	    		// Indicates file/folder is to be skipped per user parameter Tree Exclude ID
	    		// Either because file/folder matches Tree Exclude ID  or  is grandchild/parent of something with that
	    		log.info("Skipping " + driveFile.getName() + " because parents are null.  Likely due to filter excluding file/folder or a parent/grandparent/great grandparent/etc...");
	    		continue;
	    	}

			
			
			if(null == destinationBaseDirectory) {
				// Only doing a tree listing
				System.out.print(driveFile.getId());
				System.out.print('\t');
				System.out.print(driveFile.getName());
				System.out.print('\t');
				System.out.print(String.join(File.separator, fileParents));
				System.out.print('\t');
				System.out.print(driveFile.getMimeType());
				System.out.print('\t');
				System.out.print(driveFile.getModifiedTime());
				System.out.println();
			} else {
				// Doing a download
				Path dest = destinationBaseDirectory;
				for(final String ancestor : fileParents) {
					dest = FileUtilities.compatibleFilePath(dest, ancestor);
				}
				dest = FileUtilities.compatibleFilePath(dest, driveFile.getName());
				
		    	if(!dest.getFileName().toString().equals(driveFile.getName())) {
		    		log.warn("Had to change filename from " + driveFile.getName() + " to " + dest.getFileName() + " for file system compliance");
		    	}

		    	log.info("Downloading " + driveFile.getName());
		    	
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
	}
	
	
	/**
	 * Only looks at first parent each time.
	 * 
	 * @param driveFile
	 * @return List of parents in order of  <root>\ParentLvl1\parentLvl2\Parentlvl3\... but excludes <root> as it would not have a specific user-defined name.  Returns null if one of parents or driveFile itself match treeExcludeID
	 */
	private static List<String> getParentNamesFromRootToImmediateParent(final LinkedList<String> parentNames, com.google.api.services.drive.model.File driveFile, final Map<String,com.google.api.services.drive.model.File> googleFileMap, final String rootFolderFileId, final String treeExcludeID) {
		if(driveFile.getId().equals(treeExcludeID)) {
			return null;  // Signals should be excluded
		}
		
		if(null != driveFile.getParents() && !driveFile.getParents().isEmpty()) {
			final String firstParentID = driveFile.getParents().iterator().next();  // First parent

			log.debug(driveFile.getName() + " has first parent ID of " + firstParentID);
			
			if(firstParentID.equals(treeExcludeID)) {
				log.trace(driveFile.getName() + " has parent with ID matching tree exclude ID of " + treeExcludeID);
				return null;  // Signals should be excluded
			}
			
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
			
			return getParentNamesFromRootToImmediateParent(parentNames, firstParent, googleFileMap, rootFolderFileId, treeExcludeID);
		} else {
			return parentNames;
		}
	}
	private static List<String> getParentNamesFromRootToImmediateParent(com.google.api.services.drive.model.File driveFile, final Map<String,com.google.api.services.drive.model.File> googleFileMap, final String rootFolderFileId, final String treeExcludeID) {
		return getParentNamesFromRootToImmediateParent(new LinkedList<String>(), driveFile, googleFileMap, rootFolderFileId, treeExcludeID);
	}
}
