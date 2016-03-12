package com.rodneybeede.software.backupmygoogledrive;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.apache.log4j.Logger;

public class FileUtilities {
	private static final Logger log = Logger.getLogger(FileUtilities.class);
	
	/**
	 * Because various operation systems and file systems have different rules on what characters are valid in a
	 * filename and also some have reserved words (e.g. Windows reserves "nul", "con", and others) we must provide a
	 * compatible filename that is allowed on the running platform and file system.
	 * 
	 * <p>
	 * In testing for invalid characters it is assumed that the character "_" is not forbidden.
	 * </p>
	 * 
	 * <p>
	 * Another important point is that the length of the created Path could possibly be too long for the file system
	 * which results in a return of null since this method can't create a possible Path.
	 * </p>
	 * 
	 * @param destinationDirectory The directory (and therefore file system) where we will be placing filename
	 * @param filename The candidate filename that we may have to change
	 * @return A Path with filename resolved into destinationDirectory (via {@link Path#resolve(String)}) but with
	 * 	any potential illegal characters or names removed.
	 * <p>May return null if no suitable Path could be formed.</p>
	 * 
	 * @throws NullPointerException if either argument is null
	 */
	public static Path compatibleFilePath(final Path destinationDirectory, final String filename) {
		// We'll make multiple attempts and return as soon as we are successful
		
		// Attempt:  Try a simple combination assuming filename will work as provided
    	try {
    		return destinationDirectory.resolve(filename);
    	} catch(final InvalidPathException excep) {
    		// Could be invalid character or reserved filename or path too long or ...
    		// We will try again and ignore this exception for now
    		log.trace("Attempt failed (will retry alternatives):  destinationDirectory=" + destinationDirectory + "   filename=" + filename, excep);
    	}
    	
    	
    	// Attempt:  Try testing for and removing any invalid characters in filename
    	{  // scope out
    	final StringBuilder candidate = new StringBuilder(filename.length());
    	for(int i = 0; i < filename.length(); i++) {
    		try {
    			// We always prepend with _ (assumption that file system allows files with letter _)
    			// such that we don't end up with filename.charAt(i) being  .  which is usually an invalid name
    			// So we always test a 3 character filename starting and ending with _
    			// We surround the character so characters like space (dec 32) are possible
    			destinationDirectory.resolve("_" + String.valueOf(filename.charAt(i) + "_"));
    			// This character is okay
    			candidate.append(filename.charAt(i));
    		} catch(final InvalidPathException excep) {
    			// This character was not okay so don't keep it
    			continue;
    		}
    	}
    	
    	if(filename.length() != candidate.length()) {
    		log.trace("Removed characters from '''" + filename + "''' to make '''" + candidate.toString() + "'''");
    	}
    	
    	if(candidate.length() > 0) {
        	try {
        		return destinationDirectory.resolve(candidate.toString());
        	} catch(final InvalidPathException excep) {
        		// Could be reserved filename or path too long or ...
        		log.trace("Attempt failed (will retry alternatives):  destinationDirectory=" + destinationDirectory + "   candidate=" + candidate, excep);
        	}
    	}  // Not a valid filename since we remove all characters so go on to next attempt
    	}  // end of scope out
    	
    	
    	// Attempt:  Try changing the filename to not be a (likely) reserved word
    	try {
    		return destinationDirectory.resolve(filename.concat("_"));
    	} catch(final InvalidPathException excep) {
    		// Could be path too long or other issue we cannot handle
    		log.warn("Attempt failed (was final chance):  destinationDirectory=" + destinationDirectory + "   filename=" + filename, excep);
    	}


    	// Failed to find any combination that would work
    	return null;
	}
}
