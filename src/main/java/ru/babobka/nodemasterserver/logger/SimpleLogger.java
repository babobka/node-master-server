package ru.babobka.nodemasterserver.logger;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimpleLogger {

	private final Logger logger;

	public SimpleLogger(String loggerName, String runningFolder, String prefix) throws IOException {
		logger = LogBuilder.build(loggerName, runningFolder, prefix);
	}

	public void log(Level level, String message) {
		logger.log(level, message);

	}

	public void log(Level level, Exception e) {
		log(level, getStringFromException(e));
	}

	
	public void log(Object object) {
		log(object.toString());
	}
	
	public void log(String message) {
		log(Level.INFO, message);
	}

	public void log(String message, Exception e) {
		log(Level.SEVERE, message + "\t" + getStringFromException(e));
	}

	public void log(Exception e) {
		log(Level.SEVERE, getStringFromException(e));
	}
 
	private static String getStringFromException(Exception ex) {
		StringWriter errors = new StringWriter();
		ex.printStackTrace(new PrintWriter(errors));
		return errors.toString();
	}

}
