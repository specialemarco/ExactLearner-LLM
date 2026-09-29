package org.experiments.logger;

import java.util.logging.Logger;

public class SmartLogger {

    private static final Logger logger = Logger.getLogger(SmartLogger.class.getName());

    public static void log(String message) {
        logger.info(message+"\n");
    }
}
